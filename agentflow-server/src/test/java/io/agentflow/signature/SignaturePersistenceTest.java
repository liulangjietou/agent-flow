package io.agentflow.signature;

import static io.agentflow.signature.SignaturePersistenceFixtures.*;
import static org.assertj.core.api.Assertions.*;

import io.agentflow.common.DomainException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真实 JDBC 事务覆盖原件轮次、租户、单次外发身份、回调竞争和逐份文件保存的恢复。
 *
 * @author owlzhangfq@gmail.com
 */
class SignaturePersistenceTest {
    private JdbcTemplate jdbc;
    private JdbcSignatureOperationRepository repository;
    private TransactionTemplate tx;
    private SignatureOperation queued;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-persistence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        migrate(source, "latest"); jdbc = new JdbcTemplate(source); repository = repository(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); queued = seed(jdbc);
    }

    @AfterEach void stop() { jdbc.execute("SHUTDOWN"); }

    @Test void frozenRequestAndNanosecondAuthorizationSurviveTenantScopedReadAndHistory() {
        create();
        var request = queued.input().request();
        assertThat(load()).isEqualTo(queued);
        assertThat(load().input().request().authorization().authorizedAt().getNano()).isEqualTo(123456789);
        assertThat(repository.find("foreign", request.id())).isEmpty();
        assertThat(repository.forRound("foreign", request.source().applicationId(), 2, null, 10)).isEmpty();
        assertThat(repository.forRound("tenant-a", request.source().applicationId(), 2, null, 10)).containsExactly(queued);
        assertThat(repository.forRound("tenant-a", request.source().applicationId(), 2, queued, 10)).isEmpty();
        assertThat(repository.history(queued, 0, 10)).containsExactly(queued);
        assertThat(count("signature_source_document")).isEqualTo(2);
        assertThat(count("signature_result_file")).isZero();
        assertThat(repository.due(NOW)).extracting(JdbcSignatureOperationRepository.Candidate::id).containsExactly(request.id());
    }

    @ParameterizedTest @ValueSource(strings = {"application-status", "application-version", "definition-version", "round-status", "missing-round"})
    void creationRequiresTheExactCurrentlyApprovedRound(String variant) {
        String id = queued.input().request().source().applicationId().toString();
        switch (variant) {
            case "application-status" -> jdbc.update("UPDATE approval_application SET status='IN_APPROVAL' WHERE id=?", id);
            case "application-version" -> jdbc.update("UPDATE approval_application SET version=8 WHERE id=?", id);
            case "definition-version" -> jdbc.update("UPDATE approval_application SET definition_version=4 WHERE id=?", id);
            case "round-status" -> jdbc.update(
                            "UPDATE approval_submission_round SET status='REJECTED' WHERE"
                                + " application_id=?", id);
            case "missing-round" -> { jdbc.update("DELETE FROM approval_attachment_round WHERE application_id=?", id); jdbc.update("DELETE FROM approval_submission_round WHERE application_id=?", id); }
            default -> throw new AssertionError(variant);
        }
        assertCode(this::create, "CONCURRENCY_CONFLICT");
        assertThat(count("signature_operation")).isZero();
        assertThat(count("signature_operation_revision")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"unfrozen", "not-ready", "filename", "size", "fingerprint"})
    void changedOrUnfrozenOriginalCannotPartiallyCreateAnOperation(String variant) {
        String id = queued.input().request().documents().get(0).attachmentId().toString();
        switch (variant) {
            case "unfrozen" -> jdbc.update("DELETE FROM approval_attachment_round WHERE attachment_id=?", id);
            case "not-ready" -> jdbc.update("UPDATE approval_attachment SET status='FAILED' WHERE id=?", id);
            case "filename" -> jdbc.update("UPDATE approval_attachment SET filename='其他.pdf' WHERE id=?", id);
            case "size" -> jdbc.update("UPDATE approval_attachment SET byte_size=42 WHERE id=?", id);
            case "fingerprint" -> jdbc.update("UPDATE approval_attachment SET sha256=? WHERE id=?", "f".repeat(64), id);
            default -> throw new AssertionError(variant);
        }
        assertCode(this::create, "SIGNATURE_SOURCE_CHANGED");
        for (String table : List.of("signature_operation", "signature_source_document", "signature_operation_revision")) assertThat(count(table)).as(table).isZero();
    }

    @Test void duplicateAuthorizationAndConcurrentClaimsHaveOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var creates = List.of(queued, another(queued)).stream().map(value -> pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { tx.executeWithoutResult(ignored -> repository.create(value)); return true; }
                catch (DomainException error) { assertThat(error.code()).isEqualTo("SIGNATURE_OPERATION_ACTIVE"); return false; }
            })).toList();
            start.countDown(); int successes = 0; for (var future : creates) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            queued = repository.forRound("tenant-a", queued.input().request().source().applicationId(), 2, null, 10).get(0);
            var sent = queued.claim(NOW, LEASE); var claim = new CountDownLatch(1);
            var claims = List.of(1, 2).stream().map(unused -> pool.submit(() -> {
                claim.await(5, TimeUnit.SECONDS);
                try { tx.executeWithoutResult(ignored -> repository.update(sent)); return true; }
                catch (DomainException error) { assertThat(error.code()).isEqualTo("CONCURRENCY_CONFLICT"); return false; }
            })).toList();
            claim.countDown(); successes = 0; for (var future : claims) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1); assertThat(load()).isEqualTo(sent);
            assertThat(repository.history(sent, 0, 10)).extracting(SignatureOperation::version).containsExactly(1L, 2L);
            assertThat(repository.due(sent.leaseUntil().minusSeconds(1))).isEmpty();
            assertThat(repository.due(sent.leaseUntil())).hasSize(1);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void unknownAndNotFoundPreserveTheOriginalOperationAndContinueBlockingAnotherSend() {
        create(); var sent = save(queued.claim(NOW, LEASE));
        var unknown = save(sent.unavailable(SignatureOperation.Failure.TIMEOUT, NOW.plusSeconds(1)));
        var query = save(unknown.claim(unknown.nextAttemptAt(), LEASE));
        var missing = save(query.complete(receipt(query, SignatureReceipt.Status.NOT_FOUND, 0, query.updatedAt()), query.updatedAt().plusSeconds(1)));
        assertThat(load()).isEqualTo(missing);
        assertCode(() -> tx.executeWithoutResult(ignored -> repository.create(another(queued))), "SIGNATURE_OPERATION_ACTIVE");
        assertThat(save(missing.claim(missing.nextAttemptAt(), LEASE)).status()).isEqualTo(SignatureOperation.Status.QUERYING);
        assertThat(count("signature_operation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_json FROM signature_operation", String.class)).isEqualTo(JSON.write(queued.input()));
    }

    @Test void callbackWinsOverTheLateWorkerWithoutReplacingTheReceiptOrReservedFiles() {
        create(); var sent = save(queued.claim(NOW, LEASE));
        var signed = receipt(sent, SignatureReceipt.Status.SIGNED, 2, NOW.plusSeconds(1));
        var accepted = save(sent.receiveCallback(signed, NOW.plusSeconds(2)));
        var late = sent.complete(receipt(sent, SignatureReceipt.Status.PENDING, 1, NOW.plusSeconds(1)), NOW.plusSeconds(3));
        assertCode(() -> save(late), "CONCURRENCY_CONFLICT");
        assertThat(load()).isEqualTo(accepted);
        assertThat(load().receiveCallback(signed, NOW.plusSeconds(4))).isEqualTo(accepted);
        assertThat(repository.resultFiles(load())).extracting(JdbcSignatureOperationRepository.ResultFile::artifact).containsExactlyElementsOf(accepted.artifacts());
        assertThat(count("signature_operation_revision")).isEqualTo(3);
    }

    @Test void partialFileSaveSurvivesExpiredClaimAndOnlyCompleteReadyFilesCanFinish() {
        var collecting = collecting(); var download = save(collecting.claim(collecting.nextAttemptAt(), LEASE));
        var first = download.artifacts().get(0); var second = download.artifacts().get(1);
        tx.executeWithoutResult(ignored -> repository.markArtifactReady(download, first, download.updatedAt().plusSeconds(1)));
        var partial = repository.resultFiles(load());
        assertThat(partial).extracting(JdbcSignatureOperationRepository.ResultFile::ready).containsExactly(true, false);
        assertThat(partial.get(0).savedVersion()).isEqualTo(download.version());
        assertCode(() -> save(download.completeFiles(download.artifacts(), download.updatedAt().plusSeconds(2))), "CONCURRENCY_CONFLICT");
        var expired = save(download.expire(download.leaseUntil()));
        var next = save(expired.claim(expired.nextAttemptAt(), LEASE));
        assertThat(next.status()).isEqualTo(SignatureOperation.Status.FETCHING_FILES);
        assertThat(next.artifacts()).isEqualTo(collecting.artifacts());
        tx.executeWithoutResult(ignored -> {
            repository.markArtifactReady(next, first, next.updatedAt().plusSeconds(1));
            repository.markArtifactReady(next, second, next.updatedAt().plusSeconds(1));
        });
        assertThat(repository.resultFiles(load()).get(0)).isEqualTo(partial.get(0));
        var done = save(next.completeFiles(next.artifacts(), next.updatedAt().plusSeconds(2)));
        assertThat(done.status()).isEqualTo(SignatureOperation.Status.SIGNED);
        assertThat(repository.due(NOW.plusSeconds(7200))).isEmpty();
        assertThat(jdbc.queryForList("SELECT status FROM stored_document_inventory", String.class)).containsOnly("READY").hasSize(4);
        tx.executeWithoutResult(ignored -> repository.create(another(queued)));
        assertThat(count("signature_operation")).isEqualTo(2);
    }

    @Test void lateOrWrongFileConfirmationCannotChangeSavedState() {
        var collecting = collecting(); var download = save(collecting.claim(collecting.nextAttemptAt(), LEASE));
        var file = download.artifacts().get(0);
        assertCode(() -> tx.executeWithoutResult(ignored -> repository.markArtifactReady(download, file, download.leaseUntil())), "CONCURRENCY_CONFLICT");
        var replaced = new SignatureOperation.StoredArtifact(file.documentId(), UUID.randomUUID(), file.size(), file.sha256());
        assertCode(() -> tx.executeWithoutResult(ignored -> repository.markArtifactReady(download, replaced, download.updatedAt().plusSeconds(1))), "CONCURRENCY_CONFLICT");
        var next = save(download.expire(download.leaseUntil()));
        assertCode(() -> tx.executeWithoutResult(ignored -> repository.markArtifactReady(download, file, next.updatedAt())), "CONCURRENCY_CONFLICT");
        assertThat(repository.resultFiles(next)).noneMatch(JdbcSignatureOperationRepository.ResultFile::ready);
    }

    @Test void cancelledOriginalRemainsInHistoryWhileNewAuthorizationGetsANewIdentity() {
        create(); var cancelled = save(queued.cancelUnsent(NOW.plusSeconds(1)));
        var another = another(queued); tx.executeWithoutResult(ignored -> repository.create(another));
        assertThat(repository.find("tenant-a", cancelled.input().request().id())).contains(cancelled);
        var page = repository.forRound("tenant-a", queued.input().request().source().applicationId(), 2, null, 1);
        assertThat(page).hasSize(1);
        var next = repository.forRound("tenant-a", queued.input().request().source().applicationId(), 2, page.get(0), 1);
        assertThat(next).hasSize(1).doesNotContainAnyElementsOf(page);
        assertThat(repository.history(cancelled, 1, 1)).containsExactly(cancelled);
    }

    @Test void mutationsRequireAnExistingTransaction() {
        assertThatThrownBy(() -> repository.create(queued)).isInstanceOf(IllegalTransactionStateException.class);
        create();
        assertThatThrownBy(() -> repository.update(queued.claim(NOW, LEASE))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.lock("tenant-a", queued.input().request().id())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void originalTargetAndTerminalHistoryCannotBeRewritten() {
        create(); var sent = queued.claim(NOW, LEASE);
        var changed = new SignatureOperation(new SignatureOperation.Input(sent.input().request(), "f".repeat(64)), sent.version(), sent.status(), sent.attempts(),
                sent.updatedAt(), sent.nextAttemptAt(), sent.leaseUntil(), sent.receipt(), sent.failure(), sent.artifacts());
        assertCode(() -> save(changed), "CONCURRENCY_CONFLICT");
        var cancelled = save(queued.cancelUnsent(NOW.plusSeconds(1)));
        var terminalRewrite = new SignatureOperation(cancelled.input(), cancelled.version() + 1, cancelled.status(), cancelled.attempts(),
                cancelled.updatedAt().plusSeconds(1), null, null, null, null, List.of());
        assertCode(() -> save(terminalRewrite), "CONCURRENCY_CONFLICT");
        assertThat(load()).isEqualTo(cancelled); assertThat(count("signature_operation_revision")).isEqualTo(2);
    }

    @Test void copiedAttachmentReferencesKeepTheOriginalPhysicalContentAndCannotCrossTenants() {
        var original = queued.input().request();
        var copies = original.documents().stream().map(document -> new SignatureRequest.Document(UUID.randomUUID(), document.contentId(), document.fieldPath(),
                document.filename(), document.size(), document.sha256())).toList();
        var childRequest = new SignatureRequest(UUID.randomUUID(), original.tenantId(), new SignatureRequest.Source(UUID.randomUUID(), 2, 7, "contract", 3),
                original.authorization(), copies, original.signers());
        seed(jdbc, childRequest);
        var child = SignatureOperation.queue(new SignatureOperation.Input(childRequest, queued.input().targetDigest()), NOW);
        tx.executeWithoutResult(ignored -> repository.create(child));
        assertThat(repository.find("tenant-a", childRequest.id())).contains(child);
        assertThat(jdbc.queryForList("SELECT id FROM stored_document_inventory", String.class)).hasSize(2);
        var foreignDocuments = original.documents().stream().map(document -> {
            UUID id = UUID.randomUUID(); return new SignatureRequest.Document(id, id, document.fieldPath(), document.filename(), document.size(), document.sha256());
        }).toList();
        var foreignRequest = new SignatureRequest(UUID.randomUUID(), "tenant-b", new SignatureRequest.Source(UUID.randomUUID(), 2, 7, "contract", 3),
                original.authorization(), foreignDocuments, original.signers());
        seed(jdbc, foreignRequest);
        var copy = copies.get(0);
        var foreign = foreignRequest.documents().stream().filter(document -> document.sha256().equals(copy.sha256())).findFirst().orElseThrow();
        assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE signature_source_document SET original_content_id=?"
                                            + " WHERE operation_id=? AND attachment_id=?",
                foreign.contentId().toString(), childRequest.id().toString(), copy.attachmentId().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.find("tenant-a", childRequest.id())).contains(child);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "claim", "reservation"})
    void laterHistoryOrFileFailureRollsBackTheWholeTransition(String stage) {
        if (stage.equals("create")) {
            jdbc.execute(
                    "ALTER TABLE signature_operation_revision ADD CONSTRAINT reject_first"
                        + " CHECK(version<>1)");
            assertThatThrownBy(this::create).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(count("signature_operation")).isZero(); assertThat(count("signature_source_document")).isZero();
        } else {
            create();
            if (stage.equals("claim")) {
                jdbc.execute(
                        "ALTER TABLE signature_operation_revision ADD CONSTRAINT reject_claim"
                            + " CHECK(version<>2)");
                assertThatThrownBy(() -> save(queued.claim(NOW, LEASE))).isInstanceOf(DataIntegrityViolationException.class);
                assertThat(load()).isEqualTo(queued);
            } else {
                var sent = save(queued.claim(NOW, LEASE));
                jdbc.execute(
                        "ALTER TABLE signature_result_file ADD CONSTRAINT reject_reservation"
                            + " CHECK(byte_size<0)");
                assertThatThrownBy(() -> save(sent.complete(receipt(sent, SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)), NOW.plusSeconds(1))))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThat(load()).isEqualTo(sent); assertThat(count("signature_result_file")).isZero();
                assertThat(count("signature_operation_revision")).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"request-digest", "target-digest", "source-row", "result-row", "result-receipt"})
    void restorationRejectsChangedIndexesSourcesOrResults(String variant) {
        var collecting = collecting();
        switch (variant) {
            case "request-digest" -> jdbc.update("UPDATE signature_operation SET request_digest=?", "f".repeat(64));
            case "target-digest" -> jdbc.update("UPDATE signature_operation SET target_digest=?", "f".repeat(64));
            case "source-row" -> jdbc.update("UPDATE signature_source_document SET filename='变更.pdf'");
            case "result-row" -> jdbc.update("DELETE FROM signature_result_file WHERE document_id=?", collecting.artifacts().get(0).documentId().toString());
            case "result-receipt" -> jdbc.update("UPDATE signature_result_file SET receipt_digest=?", "f".repeat(64));
            default -> throw new AssertionError(variant);
        }
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"active-guard", "next-attempt", "lease"})
    void databaseCannotHideAnActiveOperationByAcceptingNullScheduleFacts(String variant) {
        create();
        if (variant.equals("lease")) save(queued.claim(NOW, LEASE));
        String column = switch (variant) { case "active-guard" -> "active_guard"; case "next-attempt" -> "next_attempt_at"; default -> "lease_until"; };
        assertThatThrownBy(() -> jdbc.update("UPDATE signature_operation SET " + column + "=NULL")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void indexPrecisionDoesNotRejectAValidSubMicrosecondLeaseOrLoseOriginalTimes() {
        create(); var sent = save(queued.claim(NOW, Duration.ofNanos(1)));
        assertThat(load()).isEqualTo(sent);
        assertThat(load().leaseUntil()).isEqualTo(NOW.plusNanos(1));
        assertThat(save(sent.expire(sent.leaseUntil())).status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
    }

    private void create() { tx.executeWithoutResult(ignored -> repository.create(queued)); }
    private SignatureOperation save(SignatureOperation operation) { tx.executeWithoutResult(ignored -> repository.update(operation)); return operation; }
    private SignatureOperation load() { return repository.find("tenant-a", queued.input().request().id()).orElseThrow(); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private SignatureOperation collecting() {
        create(); var sent = save(queued.claim(NOW, LEASE));
        return save(sent.complete(receipt(sent, SignatureReceipt.Status.SIGNED, 1, NOW.plusSeconds(1)), NOW.plusSeconds(1)));
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
