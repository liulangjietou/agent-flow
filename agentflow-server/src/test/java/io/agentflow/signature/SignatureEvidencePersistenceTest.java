package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.PropertyPlaceholderHelper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 原始签名证据与状态同事务保存，非空升级和独立恢复后仍按原公钥验证。
 * @author owlzhangfq@gmail.com
 */
class SignatureEvidencePersistenceTest {
    @TempDir Path temporary;
    private final java.security.KeyPair pair = keyPair();
    private final SignatureProfile profile = profile(pair);
    private final SignatureOperation.Input input = input(profile);
    private final SignatureReceiptVerifier verifier = new SignatureReceiptVerifier(MAPPER);
    private JdbcTemplate jdbc;
    private JdbcSignatureOperationRepository operations;
    private JdbcSignatureEvidenceRepository evidence;
    private TransactionTemplate tx;
    private SignatureOperation sent;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-evidence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        SignaturePersistenceFixtures.migrate(source, "112"); jdbc = new JdbcTemplate(source); operations = SignaturePersistenceFixtures.repository(source);
        evidence = repository(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        SignaturePersistenceFixtures.seed(jdbc, input.request()); var queued = SignatureOperation.queue(input, NOW); sent = queued.claim(NOW, SignaturePersistenceFixtures.LEASE);
        tx.executeWithoutResult(ignored -> { operations.create(queued); operations.update(sent); });
    }
    @AfterEach void stop() { jdbc.execute("SHUTDOWN"); }

    @Test void acceptedProofRetainsExactBytesNanosecondsAndOriginalRevisionWithoutTenantFallback() {
        var verified = proof(SignatureReceipt.Status.SIGNED); var accepted = save(verified);
        assertThat(evidence.find("tenant-a", input.request().id(), verified.evidence().digest())).contains(verified);
        assertThat(evidence.find("tenant-b", input.request().id(), verified.evidence().digest())).isEmpty();
        assertThat(evidence.find("tenant-a", UUID.randomUUID(), verified.evidence().digest())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT accepted_version FROM signature_receipt_evidence", Long.class)).isEqualTo(accepted.version());
        assertThat(operations.resultFiles(accepted)).hasSize(1);
        assertThat(verified.evidence().verifiedAt().getNano()).isEqualTo(123456789);
        assertThat(JSON.write(verified.evidence())).doesNotContain(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    }

    @Test void duplicateDeliveryPreservesFirstObservationEvenAfterFileCollectionStarts() {
        var first = proof(SignatureReceipt.Status.SIGNED); var accepted = save(first);
        var fetch = accepted.claim(accepted.nextAttemptAt(), SignaturePersistenceFixtures.LEASE);
        var later = new SignatureReceiptVerifier.Evidence(profile, input.targetDigest(), first.evidence().payloadBase64(), first.evidence().signatureBase64(), NOW.plusSeconds(20));
        var stored = tx.execute(ignored -> { operations.update(fetch); return evidence.append(fetch, later); });
        assertThat(stored).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT accepted_version FROM signature_receipt_evidence", Long.class)).isEqualTo(accepted.version());
    }

    @Test void stateEvidenceAndResultReservationsRollBackTogether() {
        var verified = proof(SignatureReceipt.Status.SIGNED); var accepted = sent.complete(verified.receipt(), NOW.plusSeconds(2));
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
            operations.update(accepted); evidence.append(accepted, verified.evidence()); throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.find("tenant-a", input.request().id())).contains(sent);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_result_file", Integer.class)).isZero();
        assertThat(operations.history(sent, 0, 10)).hasSize(2);
        assertThatThrownBy(() -> evidence.append(accepted, verified.evidence())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void aValidButUnacceptedReceiptCannotBeAttachedToAnotherState() {
        var verified = proof(SignatureReceipt.Status.SIGNED);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> evidence.append(sent, verified.evidence()))).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo("SIGNATURE_EVIDENCE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isZero();
    }

    @Test void authenticatedNotFoundIsKeptAsAnObservationAndNeverAuthorizesResending() {
        var unknown = sent.unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        var query = unknown.claim(unknown.nextAttemptAt(), SignaturePersistenceFixtures.LEASE);
        tx.executeWithoutResult(ignored -> { operations.update(unknown); operations.update(query); });
        var bytes = body(profile, input, receipt(input, SignatureReceipt.Status.NOT_FOUND));
        var verified = verifier.verify(input, profile, bytes, sign(pair, bytes), query.updatedAt().plusSeconds(1));
        var accepted = query.complete(verified.receipt(), verified.evidence().verifiedAt());
        tx.executeWithoutResult(ignored -> { operations.update(accepted); evidence.append(accepted, verified.evidence()); });
        assertThat(evidence.find("tenant-a", input.request().id(), verified.evidence().digest())).contains(verified);
        assertThat(accepted.status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
        assertThat(accepted.claim(accepted.nextAttemptAt(), SignaturePersistenceFixtures.LEASE).status()).isEqualTo(SignatureOperation.Status.QUERYING);
        assertThat(jdbc.queryForObject("SELECT provider_revision FROM signature_receipt_evidence", Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "status", "revision", "time", "signature", "payload", "profile", "history"})
    void changedPersistedIndexesProofOrHistoryCannotMasqueradeAsVerifiedEvidence(String variant) {
        var verified = proof(SignatureReceipt.Status.SIGNED); var accepted = save(verified);
        switch (variant) {
            case "digest" -> jdbc.update("UPDATE signature_receipt_evidence SET receipt_digest=?", "0".repeat(64));
            case "status" -> jdbc.update("UPDATE signature_receipt_evidence SET status='PENDING'");
            case "revision" -> jdbc.update("UPDATE signature_receipt_evidence SET provider_revision=2");
            case "time" -> jdbc.update("UPDATE signature_receipt_evidence SET verified_at=DATEADD('SECOND',1,verified_at)");
            case "history" -> jdbc.update("UPDATE signature_operation_revision SET state_json='null' WHERE version=?", accepted.version());
            default -> {
                var old = verified.evidence();
                var changed = new SignatureReceiptVerifier.Evidence(variant.equals("profile") ? profile(keyPair()) : old.profile(), old.targetDigest(),
                        variant.equals("payload") ? Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) : old.payloadBase64(),
                        variant.equals("signature") ? Base64.getEncoder().encodeToString(new byte[64]) : old.signatureBase64(), old.verifiedAt());
                jdbc.update("UPDATE signature_receipt_evidence SET evidence_json=?", JSON.write(changed));
            }
        }
        assertThatThrownBy(() -> evidence.find("tenant-a", input.request().id(), verified.evidence().digest())).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).code()).isEqualTo("SIGNATURE_EVIDENCE_CORRUPT");
    }

    @Test void nonemptyUpgradeAddsNoFabricatedProofAndPreservesAllExistingTables() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-evidence-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        try {
            SignaturePersistenceFixtures.migrate(source, "111"); SignaturePersistenceFixtures.seed(db, input.request());
            var ops = SignaturePersistenceFixtures.repository(source); var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            transactions.executeWithoutResult(ignored -> { ops.create(SignatureOperation.queue(input, NOW)); ops.update(sent); });
            var before = new LinkedHashMap<String, List<Map<String, Object>>>();
            try (var connection = source.getConnection(); var tables = connection.getMetaData().getTables(null, connection.getSchema(), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (!name.equalsIgnoreCase("flyway_schema_history")) before.put(name, db.queryForList("SELECT * FROM \"" + name.replace("\"", "\"\"") + "\""));
                }
            }
            var inventory = db.queryForList("SELECT * FROM stored_document_inventory");
            var migration = Flyway.configure().dataSource(source).target("112").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            before.forEach((name, rows) -> assertThat(db.queryForList("SELECT * FROM \"" + name.replace("\"", "\"\"") + "\"")).as(name).containsExactlyInAnyOrderElementsOf(rows));
            assertThat(db.queryForList("SELECT * FROM stored_document_inventory")).isEqualTo(inventory);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isZero();
            assertThat(migration.migrate().migrationsExecuted).isZero(); assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            System.out.printf("Signature evidence upgrade verified, previousTables=%d, previousRows=%d%n", before.size(), before.values().stream().mapToLong(List::size).sum());
        } finally { db.execute("SHUTDOWN"); }
    }

    @Test void reopenedAndIndependentlyRestoredProofVerifiesWithoutDeploymentKeysOrProviderAccess() {
        var verified = proof(SignatureReceipt.Status.SIGNED); save(verified);
        Path dump = temporary.resolve("evidence.sql"); jdbc.execute("SCRIPT TO '" + literal(dump) + "'");
        String restoredUrl = fileUrl(temporary.resolve("restored")); var source = new DriverManagerDataSource(restoredUrl, "sa", ""); var db = new JdbcTemplate(source);
        db.execute("RUNSCRIPT FROM '" + literal(dump) + "'");
        assertThat(repository(source).find("tenant-a", input.request().id(), verified.evidence().digest())).contains(verified);
        db.execute("SHUTDOWN");
        var reopened = new DriverManagerDataSource(restoredUrl, "sa", ""); var reopenedDb = new JdbcTemplate(reopened);
        try {
            assertThat(Flyway.configure().dataSource(reopened).target("112").load().validateWithResult().validationSuccessful).isTrue();
            assertThat(repository(reopened).find("tenant-a", input.request().id(), verified.evidence().digest())).contains(verified);
            reopenedDb.update("UPDATE signature_receipt_evidence SET receipt_digest=?", "f".repeat(64));
            assertThat(evidence.find("tenant-a", input.request().id(), verified.evidence().digest())).contains(verified);
        } finally { reopenedDb.execute("SHUTDOWN"); }
    }

    private SignatureReceiptVerifier.Verified proof(SignatureReceipt.Status status) {
        var bytes = body(profile, input, receipt(input, status)); return verifier.verify(input, profile, bytes, sign(pair, bytes), NOW.plusSeconds(2));
    }
    private SignatureOperation save(SignatureReceiptVerifier.Verified verified) {
        var accepted = sent.complete(verified.receipt(), NOW.plusSeconds(2));
        tx.executeWithoutResult(ignored -> { operations.update(accepted); evidence.append(accepted, verified.evidence()); }); return accepted;
    }
    private JdbcSignatureEvidenceRepository repository(DataSource source) {
        var proxy = new ProxyFactory(new JdbcSignatureEvidenceRepository(new JdbcTemplate(source), JSON, SignaturePersistenceFixtures.repository(source), new SignatureReceiptVerifier(MAPPER)));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (JdbcSignatureEvidenceRepository) proxy.getProxy();
    }
    private String fileUrl(Path path) {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        String value = new PropertyPlaceholderHelper("${", "}", ":", '\\', true).replacePlaceholders(yaml.getObject().getProperty("spring.datasource.url"), key -> null);
        assertThat(value).startsWith("jdbc:h2:file:./data/agentflow"); return value.replace("./data/agentflow", path.toString());
    }
    private static String literal(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }
}
