package io.agentflow.signature;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.PropertyPlaceholderHelper;

import io.agentflow.storage.LocalDocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际 HTTP、真实文件及 H2 共同验证丢失回包和部分文件恢复；不冒充安装包进程强退或真实供应商验收。
 * @author owlzhangfq@gmail.com
 */
class SignatureHttpRecoveryTest {
    @TempDir Path temporary;

    @Test void lostResponseAndPairedRestoreKeepOneSubmissionAndRecoverPublishedButUnacknowledgedResults() throws Exception {
        Path sourceFiles = temporary.resolve("source-files"); String sourceUrl = fileUrl(temporary.resolve("source-db"));
        try (var provider = new SignatureHttpFixture(sourceFiles)) {
            var initial = harness(sourceUrl); Flyway.configure().dataSource(initial.jdbc().getDataSource()).target("112").load().migrate();
            SignaturePersistenceFixtures.seed(initial.jdbc(), provider.input.request());
            var queued = SignatureOperation.queue(provider.input, NOW); var sent = queued.claim(NOW, Duration.ofSeconds(15));
            initial.tx().executeWithoutResult(ignored -> { initial.operations().create(queued); initial.operations().update(sent); });
            provider.mode = SignatureHttpFixture.Mode.DROP;
            assertThat(provider.gateway().submit(sent)).isEqualTo(new SignatureGateway.Unavailable(SignatureOperation.Failure.CONNECTION));
            var unknown = sent.unavailable(SignatureOperation.Failure.CONNECTION, provider.clock.now);
            initial.tx().executeWithoutResult(ignored -> initial.operations().update(unknown)); initial.jdbc().execute("SHUTDOWN");

            var reopened = harness(sourceUrl);
            try {
                var loaded = reopened.operations().find("tenant-a", provider.input.request().id()).orElseThrow(); assertThat(loaded).isEqualTo(unknown);
                provider.clock.now = loaded.nextAttemptAt(); provider.mode = SignatureHttpFixture.Mode.NORMAL;
                var query = loaded.claim(provider.clock.now, Duration.ofSeconds(15)); reopened.tx().executeWithoutResult(ignored -> reopened.operations().update(query));
                var observation = (SignatureGateway.Observed) provider.gateway().query(query);
                var collecting = query.complete(observation.verified().receipt(), provider.clock.now);
                reopened.tx().executeWithoutResult(ignored -> { reopened.operations().update(collecting); reopened.evidence().append(collecting, observation.verified().evidence()); });
                var fetching = collecting.claim(provider.clock.now, Duration.ofSeconds(15)); reopened.tx().executeWithoutResult(ignored -> reopened.operations().update(fetching));
                var first = fetching.artifacts().get(0);
                assertThat(provider.gateway().collect(fetching, observation.verified().evidence(), first)).isEqualTo(new SignatureGateway.Stored(first));
                assertThat(reopened.operations().resultFiles(fetching)).allMatch(value -> !value.ready());

                // 模拟文件发布后、READY 短事务之前退出；配套恢复必须保留这个预留文件。
                Path script = temporary.resolve("paired.sql"), restoredFiles = temporary.resolve("restored-files"); Files.createDirectories(restoredFiles);
                var sourceSnapshot = snapshot(reopened.jdbc()); var sourceBytes = hashes(sourceFiles);
                reopened.jdbc().execute("SCRIPT TO '" + literal(script) + "'");
                try (var paths = Files.list(sourceFiles)) { for (var file : paths.toList()) Files.copy(file, restoredFiles.resolve(file.getFileName())); }
                assertThat(sourceBytes).hasSize(provider.originals.size() + 1);

                var restored = harness(fileUrl(temporary.resolve("restored-db")));
                try {
                    restored.jdbc().execute("RUNSCRIPT FROM '" + literal(script) + "'");
                    assertThat(Flyway.configure().dataSource(restored.jdbc().getDataSource()).target("112").load().validateWithResult().validationSuccessful).isTrue();
                    var originalProof = restored.evidence().find("tenant-a", provider.input.request().id(), observation.verified().evidence().digest()).orElseThrow();
                    assertThat(originalProof).isEqualTo(observation.verified());
                    var restoredClaim = restored.operations().find("tenant-a", provider.input.request().id()).orElseThrow(); assertThat(restoredClaim).isEqualTo(fetching);
                    provider.clock.now = restoredClaim.leaseUntil(); var expired = restoredClaim.expire(provider.clock.now);
                    var retry = expired.claim(expired.nextAttemptAt(), Duration.ofSeconds(15)); provider.clock.now = retry.updatedAt();
                    restored.tx().executeWithoutResult(ignored -> { restored.operations().update(expired); restored.operations().update(retry); });
                    var store = new LocalDocumentStore(restoredFiles.toString(), SignatureReceipt.MAX_ARTIFACT_BYTES);
                    var gateway = new HttpSignatureGateway(provider.configuration, JSON, provider.verifier, store, provider.clock);
                    provider.configuration.setEnabled(false);
                    assertThat(gateway.collect(retry, originalProof.evidence(), first)).isEqualTo(new SignatureGateway.Stored(first));
                    restored.tx().executeWithoutResult(ignored -> restored.operations().markArtifactReady(retry, first, provider.clock.now));
                    assertThat(provider.calls("artifact")).isEqualTo(1);
                    provider.configuration.setEnabled(true); var second = retry.artifacts().get(1);
                    assertThat(gateway.collect(retry, originalProof.evidence(), second)).isEqualTo(new SignatureGateway.Stored(second));
                    restored.tx().executeWithoutResult(ignored -> restored.operations().markArtifactReady(retry, second, provider.clock.now));
                    var signed = retry.completeFiles(retry.artifacts(), provider.clock.now); restored.tx().executeWithoutResult(ignored -> restored.operations().update(signed));
                    assertThat(restored.operations().find("tenant-a", provider.input.request().id())).contains(signed);
                    assertThat(signed.status()).isEqualTo(SignatureOperation.Status.SIGNED);
                    assertThat(restored.operations().resultFiles(signed)).allMatch(JdbcSignatureOperationRepository.ResultFile::ready);
                    assertThat(restored.evidence().find("tenant-a", provider.input.request().id(), originalProof.evidence().digest())).contains(originalProof);
                    for (var file : signed.artifacts()) {
                        byte[] bytes = Files.readAllBytes(restoredFiles.resolve(file.contentId() + ".bin"));
                        assertThat(bytes).containsExactly(provider.results.get(file.documentId())); assertThat(SignatureHttpFixture.sha256(bytes)).isEqualTo(file.sha256());
                        assertThat(provider.originals).doesNotContainKey(file.contentId());
                    }
                    for (var original : provider.originals.entrySet()) assertThat(Files.readAllBytes(restoredFiles.resolve(original.getKey() + ".bin"))).containsExactly(original.getValue());
                    assertThat(snapshot(reopened.jdbc())).isEqualTo(sourceSnapshot); assertThat(hashes(sourceFiles)).isEqualTo(sourceBytes);
                    assertThat(provider.calls("submit")).isEqualTo(1); assertThat(provider.calls("query")).isEqualTo(1); assertThat(provider.calls("artifact")).isEqualTo(2);
                    System.out.printf("Signature HTTP paired recovery verified, submissions=%d, queries=%d, downloads=%d, originals=%d, results=%d%n",
                            provider.calls("submit"), provider.calls("query"), provider.calls("artifact"), provider.originals.size(), signed.artifacts().size());
                } finally { restored.jdbc().execute("SHUTDOWN"); }
            } finally { reopened.jdbc().execute("SHUTDOWN"); }
        }
    }

    private Harness harness(String url) {
        var source = new DriverManagerDataSource(url, "sa", ""); var jdbc = new JdbcTemplate(source); var operations = SignaturePersistenceFixtures.repository(source);
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(new JdbcSignatureEvidenceRepository(jdbc, JSON, operations, new SignatureReceiptVerifier(MAPPER)));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return new Harness(jdbc, operations, (JdbcSignatureEvidenceRepository) proxy.getProxy(), new TransactionTemplate(manager));
    }
    private Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("signature_operation", "signature_source_document", "signature_operation_revision", "signature_result_file", "signature_receipt_evidence")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1,2,3"));
        }
        return result;
    }
    private Map<String, String> hashes(Path directory) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var paths = Files.list(directory)) { for (var file : paths.toList()) result.put(file.getFileName().toString(), SignatureHttpFixture.sha256(Files.readAllBytes(file))); }
        return result;
    }
    private String fileUrl(Path path) {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        String value = new PropertyPlaceholderHelper("${", "}", ":", '\\', true).replacePlaceholders(yaml.getObject().getProperty("spring.datasource.url"), key -> null);
        assertThat(value).startsWith("jdbc:h2:file:./data/agentflow"); return value.replace("./data/agentflow", path.toString());
    }
    private static String literal(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }

    /**
     * 每次重新创建事务代理和仓储，恢复验证不复用旧进程中的操作对象或数据库连接。
     * @author owlzhangfq@gmail.com
     */
    record Harness(JdbcTemplate jdbc, JdbcSignatureOperationRepository operations, JdbcSignatureEvidenceRepository evidence, TransactionTemplate tx) { }
}
