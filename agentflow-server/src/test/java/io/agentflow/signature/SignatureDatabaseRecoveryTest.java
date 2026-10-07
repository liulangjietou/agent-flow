package io.agentflow.signature;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.PropertyPlaceholderHelper;

import java.nio.file.Path;
import java.util.List;

import static io.agentflow.signature.SignaturePersistenceFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用实际默认 H2 文件库参数重开和独立 SQL 恢复，只验证数据库事实，不宣称文件字节或外部签署已验收。
 * @author owlzhangfq@gmail.com
 */
class SignatureDatabaseRecoveryTest {
    @TempDir Path temporary;

    @Test void reopeningAndIndependentRestoreRetainOriginalClaimsAndPartialFileAcknowledgements() {
        String url = fileUrl(temporary.resolve("source"));
        var source = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(source); migrate(source, "latest");
        var repository = repository(source); var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var queued = seed(jdbc); var missingResponse = seed(jdbc);
        tx.executeWithoutResult(ignored -> { repository.create(queued); repository.create(missingResponse); });
        var sent = queued.claim(NOW, LEASE); tx.executeWithoutResult(ignored -> repository.update(sent));
        var collecting = sent.complete(receipt(sent, SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)), NOW.plusSeconds(1));
        tx.executeWithoutResult(ignored -> repository.update(collecting));
        var fetching = collecting.claim(collecting.nextAttemptAt(), LEASE);
        var unconfirmed = missingResponse.claim(NOW, LEASE);
        tx.executeWithoutResult(ignored -> { repository.update(fetching); repository.update(unconfirmed); });
        tx.executeWithoutResult(ignored -> repository.markArtifactReady(fetching, fetching.artifacts().get(0), fetching.updatedAt().plusSeconds(1)));
        var files = repository.resultFiles(fetching);
        var history = repository.history(fetching, 0, 20);
        var tables = List.of("signature_operation", "signature_source_document", "signature_operation_revision", "signature_result_file");
        var snapshot = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        Path dump = temporary.resolve("database.sql");
        jdbc.execute("SCRIPT TO '" + literal(dump) + "'"); jdbc.execute("SHUTDOWN");

        var reopened = new DriverManagerDataSource(url, "sa", "");
        var reopenedJdbc = new JdbcTemplate(reopened); var reopenedRepository = repository(reopened);
        assertThat(Flyway.configure().dataSource(reopened).load().validateWithResult().validationSuccessful).isTrue();
        assertThat(reopenedRepository.find("tenant-a", queued.input().request().id())).contains(fetching);
        assertThat(reopenedRepository.find("tenant-a", missingResponse.input().request().id())).contains(unconfirmed);
        assertThat(reopenedRepository.resultFiles(fetching)).isEqualTo(files);
        assertThat(reopenedRepository.history(fetching, 0, 20)).isEqualTo(history);

        var restored = new DriverManagerDataSource(fileUrl(temporary.resolve("restored")), "sa", "");
        var restoredJdbc = new JdbcTemplate(restored);
        try {
            restoredJdbc.execute("RUNSCRIPT FROM '" + literal(dump) + "'");
            var restoredRepository = repository(restored); var restoredTx = new TransactionTemplate(new DataSourceTransactionManager(restored));
            assertThat(Flyway.configure().dataSource(restored).load().validateWithResult().validationSuccessful).isTrue();
            for (int index = 0; index < tables.size(); index++) {
                assertThat(restoredJdbc.queryForList("SELECT * FROM " + tables.get(index))).as(tables.get(index)).containsExactlyInAnyOrderElementsOf(snapshot.get(index));
            }
            assertThat(restoredRepository.resultFiles(fetching)).isEqualTo(files);
            var expired = fetching.expire(fetching.leaseUntil());
            restoredTx.executeWithoutResult(ignored -> restoredRepository.update(expired));
            var retry = expired.claim(expired.nextAttemptAt(), LEASE);
            restoredTx.executeWithoutResult(ignored -> restoredRepository.update(retry));
            assertThat(retry.status()).isEqualTo(SignatureOperation.Status.FETCHING_FILES);
            assertThat(retry.artifacts()).isEqualTo(fetching.artifacts());
            restoredTx.executeWithoutResult(ignored -> restoredRepository.markArtifactReady(retry, retry.artifacts().get(1), retry.updatedAt().plusSeconds(1)));
            var complete = retry.completeFiles(retry.artifacts(), retry.updatedAt().plusSeconds(2));
            restoredTx.executeWithoutResult(ignored -> restoredRepository.update(complete));
            assertThat(restoredRepository.find("tenant-a", queued.input().request().id())).contains(complete);
            assertThat(restoredRepository.resultFiles(complete).get(0)).isEqualTo(files.get(0));
            var unknown = unconfirmed.expire(unconfirmed.leaseUntil());
            restoredTx.executeWithoutResult(ignored -> restoredRepository.update(unknown));
            var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
            restoredTx.executeWithoutResult(ignored -> restoredRepository.update(query));
            assertThat(query.status()).isEqualTo(SignatureOperation.Status.QUERYING);
            assertThat(query.input()).isEqualTo(missingResponse.input());
            for (int index = 0; index < tables.size(); index++) {
                assertThat(reopenedJdbc.queryForList("SELECT * FROM " + tables.get(index))).as("unchanged source " + tables.get(index)).containsExactlyInAnyOrderElementsOf(snapshot.get(index));
            }
        } finally { restoredJdbc.execute("SHUTDOWN"); reopenedJdbc.execute("SHUTDOWN"); }
    }

    private String fileUrl(Path path) {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        String value = new PropertyPlaceholderHelper("${", "}", ":", '\\', true)
                .replacePlaceholders(yaml.getObject().getProperty("spring.datasource.url"), key -> null);
        assertThat(value).startsWith("jdbc:h2:file:./data/agentflow");
        return value.replace("./data/agentflow", path.toString());
    }
    private static String literal(Path path) { return path.toAbsolutePath().toString().replace("'", "''"); }
}
