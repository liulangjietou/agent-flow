package io.agentflow.signature;

import io.agentflow.approval.JdbcApplicationRepository;
import io.agentflow.auth.DeferredActorAuthentication;
import io.agentflow.auth.DeferredActorAuthentication.Kind;
import io.agentflow.auth.DeferredActorAuthentication.LoginReference;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.storage.LocalDocumentStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 H2 事务、HTTP 与文件验证后台恢复；认证与资源决策单独由专门用例覆盖。
 * @author owlzhangfq@gmail.com
 */
class SignatureWorkerIntegrationTest {
    @TempDir Path directory;
    private final CurrentActor actors = new CurrentActor();
    private final Actor alice = new Actor("tenant-a", "alice", Set.of("EMPLOYEE"));
    private final LoginReference reference = new LoginReference(Kind.DEMO_LOGIN, "a".repeat(64));
    private final DeferredActorAuthentication authentication = mock(DeferredActorAuthentication.class);
    private final SignatureAccess access = mock(SignatureAccess.class);
    private SignatureHttpFixture provider;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private JdbcSignatureOperationRepository operations;
    private JdbcSignatureLoginRepository logins;
    private JdbcSignatureEvidenceRepository evidence;
    private SignatureAudit audit;
    private SignatureOperationService service;
    private SignatureWorker worker;

    @BeforeEach void setup() throws Exception {
        provider = new SignatureHttpFixture(directory.resolve("files"));
        var source = new DriverManagerDataSource("jdbc:h2:mem:signature-worker-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        Flyway.configure().dataSource(source).target("113").load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
        operations = proxy(new JdbcSignatureOperationRepository(jdbc, JSON)); logins = proxy(new JdbcSignatureLoginRepository(jdbc));
        evidence = proxy(new JdbcSignatureEvidenceRepository(jdbc, JSON, operations, provider.verifier)); audit = proxy(new SignatureAudit(jdbc, JSON));
        when(authentication.resolve(any(), eq("tenant-a"), eq("alice"), any())).thenReturn(Optional.of(alice));
        when(authentication.capture(any(), eq(alice), any())).thenReturn(reference);
        service = service(audit);
        worker = new SignatureWorker(operations, evidence, service, provider.gateway(), provider.clock);
        SignaturePersistenceFixtures.seed(jdbc, provider.input.request());
        when(access.prepare(eq(alice), eq(provider.input.request().source().applicationId()), any(), eq(NOW))).thenReturn(provider.input);
    }
    @AfterEach void close() throws Exception { actors.clear(); if (provider != null) provider.close(); }

    @Test void creationPersistsAuthorizationAndAuditAtomicallyWithoutSending() {
        actors.set(alice);
        var created = service.create(provider.input.request().source().applicationId(), command(), new MockHttpServletRequest(), NOW);
        assertThat(stored()).isEqualTo(created); assertThat(logins.find(created)).contains(reference);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).containsExactly("SIGNATURE_AUTHORIZED");
        assertThat(provider.requests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT payload_json FROM audit_event", String.class)).doesNotContain(reference.value(), "provider-company", "合同", "token");
    }

    @Test void failedAuditRollsBackSourceLoginAndOperationTogether() {
        var broken = mock(SignatureAudit.class); doThrow(new IllegalStateException("audit unavailable")).when(broken).record(any(), any(), any());
        actors.set(alice);
        assertThatThrownBy(() -> service(broken).create(provider.input.request().source().applicationId(), command(), new MockHttpServletRequest(), NOW))
                .isInstanceOf(IllegalStateException.class);
        for (String table : List.of("signature_operation", "signature_source_document", "signature_operation_revision", "signature_login_authorization"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
    }

    @Test void revokedLoginCancelsBeforeFirstSendAndMissingReferenceCannotGrantAuthority() {
        queue(); when(authentication.resolve(any(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.CANCELLED); assertThat(stored().attempts()).isZero();
        assertThat(provider.requests).isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM audit_event", String.class)).containsExactly("SIGNATURE_AUTHORIZATION_REVOKED");
        assertThat(logins.find(stored())).contains(reference);
    }

    @Test void currentFieldOrSourceRevocationCancelsButStorageFailureDoesNotInventRevocation() {
        queue();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable")).when(access).requireInitialSend(any(), any());
        worker.poll(); assertThat(stored().status()).isEqualTo(SignatureOperation.Status.QUEUED);
        doThrow(new DomainException("FORBIDDEN", "Field is hidden")).when(access).requireInitialSend(any(), any());
        worker.poll(); assertThat(stored().status()).isEqualTo(SignatureOperation.Status.CANCELLED);
        assertThat(provider.requests).isEmpty();
    }

    @Test void lostSubmitReplyThenRevokedLoginStillQueriesOriginalAndSavesBothResults() {
        queue(); provider.mode = SignatureHttpFixture.Mode.DROP; worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
        when(authentication.resolve(any(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        doThrow(new DomainException("FORBIDDEN", "Revoked after send")).when(access).requireInitialSend(any(), any());
        provider.clock.now = stored().nextAttemptAt(); provider.mode = SignatureHttpFixture.Mode.NORMAL; worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.COLLECTING);
        assertThat(evidence.forReceipt(stored()).receipt()).isEqualTo(stored().receipt());
        worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.SIGNED);
        assertThat(operations.resultFiles(stored())).hasSize(2).allMatch(JdbcSignatureOperationRepository.ResultFile::ready);
        assertThat(provider.calls("submit")).isEqualTo(1); assertThat(provider.calls("query")).isEqualTo(1); assertThat(provider.calls("artifact")).isEqualTo(2);
        for (var result : stored().artifacts()) assertThat(provider.documents.read(new LocalDocumentStore.Content(result.contentId(), result.size(), result.sha256())))
                .isEqualTo(provider.results.get(result.documentId()));
        for (var original : provider.input.request().documents()) assertThat(provider.documents.read(new LocalDocumentStore.Content(original.contentId(), original.size(), original.sha256())))
                .isEqualTo(provider.originals.get(original.attachmentId()));
        verify(access, times(1)).requireInitialSend(any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentClaimHasOneOwnerAndLateCompletionCannotOverwriteNewClaim() throws Exception {
        queue(); var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<SignatureOperation> attempt = () -> service.claim("tenant-a", provider.input.request().id(), provider.clock.now);
            var jobs = executor.invokeAll(List.of(attempt, attempt));
            var claims = new java.util.ArrayList<SignatureOperation>(); for (var job : jobs) { var claim = job.get(10, TimeUnit.SECONDS); if (claim != null) claims.add(claim); }
            assertThat(claims).hasSize(1); var original = claims.get(0);
            provider.clock.now = original.leaseUntil(); assertThat(service.claim("tenant-a", provider.input.request().id(), provider.clock.now)).isNull();
            var replacement = service.claim("tenant-a", provider.input.request().id(), provider.clock.now);
            assertThat(replacement.status()).isEqualTo(SignatureOperation.Status.QUERYING);
            service.finish(original, new SignatureGateway.Observed(provider.proof()), provider.clock.now);
            assertThat(stored()).isEqualTo(replacement);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isZero();
        } finally { executor.shutdownNow(); }
    }

    @Test void unverifiedProofRollsBackReceiptReservationsHistoryAndAudit() {
        queue(); var claim = service.claim("tenant-a", provider.input.request().id(), provider.clock.now);
        var proof = provider.proof(); var original = proof.evidence();
        var forged = new SignatureReceiptVerifier.Evidence(original.profile(), original.targetDigest(), original.payloadBase64(),
                java.util.Base64.getEncoder().encodeToString(new byte[64]), original.verifiedAt());
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
        assertThatThrownBy(() -> service.finish(claim, new SignatureGateway.Observed(new SignatureReceiptVerifier.Verified(proof.receipt(), forged)), provider.clock.now))
                .isInstanceOf(DomainException.class);
        assertThat(stored()).isEqualTo(claim); assertThat(operations.history(stored(), 0, 10)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_result_file", Integer.class)).isZero();
    }

    @Test void corruptionDuringDownloadKeepsOperationCollectingAndRecoversSameReservedFiles() {
        queue(); worker.poll(); provider.clock.now = stored().nextAttemptAt(); worker.poll();
        var reserved = stored().artifacts(); provider.mode = SignatureHttpFixture.Mode.CORRUPT_FILE; worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.COLLECTING);
        assertThat(operations.resultFiles(stored())).allMatch(value -> !value.ready());
        provider.mode = SignatureHttpFixture.Mode.NORMAL; provider.clock.now = stored().nextAttemptAt(); worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.SIGNED); assertThat(stored().artifacts()).isEqualTo(reserved);
        assertThat(provider.calls("submit")).isEqualTo(1);
    }

    @Test void cancelRechecksCurrentPermissionAndVersionAndCannotCancelClaimedOperation() {
        queue(); actors.set(alice); UUID app = provider.input.request().source().applicationId(), id = provider.input.request().id();
        assertThatThrownBy(() -> service.cancel(app, id, 2, NOW)).isInstanceOf(DomainException.class);
        doThrow(new DomainException("FORBIDDEN", "Hidden field")).when(access).requireReadable(any(), any());
        assertThatThrownBy(() -> service.cancel(app, id, 1, NOW)).isInstanceOf(DomainException.class);
        doNothing().when(access).requireReadable(any(), any()); service.claim("tenant-a", id, NOW);
        assertThatThrownBy(() -> service.cancel(app, id, 2, NOW)).isInstanceOf(DomainException.class);
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.SENDING);
    }

    @Test void missingHistoricalLoginReferenceCancelsWithoutInventingAuthentication() {
        var queued = SignatureOperation.queue(provider.input, NOW); tx.executeWithoutResult(ignored -> operations.create(queued));
        worker.poll(); assertThat(stored().status()).isEqualTo(SignatureOperation.Status.CANCELLED); verifyNoInteractions(authentication);
        assertThat(provider.requests).isEmpty();
    }

    @Test void expiredAuthorizationNeverResolvesLoginAndUnsentCancellationRetainsOriginalGrant() {
        queue(); provider.clock.now = provider.input.request().authorization().validUntil(); worker.poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.EXPIRED); verifyNoInteractions(authentication);
        assertThat(provider.requests).isEmpty(); assertThat(logins.find(stored())).contains(reference);
    }

    @Test void originalAuthorizerCanCancelButAnotherAdministratorCannot() {
        queue(); UUID app = provider.input.request().source().applicationId(), id = provider.input.request().id();
        actors.set(new Actor("tenant-a", "admin", Set.of("ADMIN")));
        assertThatThrownBy(() -> service.cancel(app, id, 1, NOW)).isInstanceOf(DomainException.class);
        actors.set(alice); var cancelled = service.cancel(app, id, 1, NOW);
        assertThat(cancelled.status()).isEqualTo(SignatureOperation.Status.CANCELLED); assertThat(logins.find(cancelled)).contains(reference);
        worker.poll(); assertThat(provider.requests).isEmpty();
        assertThat(jdbc.queryForList("SELECT actor_id FROM audit_event", String.class)).containsExactly("alice");
    }

    @Test void workerRejectsAmbientTransactionAndNetworkRunsAfterCommittedClaim() {
        queue(); assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> worker.poll())).isInstanceOf(IllegalStateException.class);
        var gateway = mock(SignatureGateway.class);
        when(gateway.submit(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(stored()).isEqualTo(call.getArgument(0));
            return new SignatureGateway.Unavailable(SignatureOperation.Failure.TIMEOUT);
        });
        new SignatureWorker(operations, evidence, service, gateway, provider.clock).poll();
        assertThat(stored().status()).isEqualTo(SignatureOperation.Status.UNKNOWN);
    }

    @Test void callbackProofFailureRollsBackStateReservedFilesHistoryAndAudit() {
        queue(); var claim = service.claim("tenant-a", provider.input.request().id(), provider.clock.now);
        var proof = provider.proof(); var original = proof.evidence();
        var forged = new SignatureReceiptVerifier.Evidence(original.profile(), original.targetDigest(), original.payloadBase64(),
                java.util.Base64.getEncoder().encodeToString(new byte[64]), original.verifiedAt());
        long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
        assertThatThrownBy(() -> service.receiveCallback(new SignatureCallbackVerifier.Callback(provider.input,
                new SignatureReceiptVerifier.Verified(proof.receipt(), forged)), provider.clock.now)).isInstanceOf(DomainException.class);
        assertThat(stored()).isEqualTo(claim); assertThat(operations.history(stored(), 0, 10)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(auditCount);
        for (String table : List.of("signature_receipt_evidence", "signature_result_file"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
    }

    @Test void concurrentDuplicateCallbacksKeepOneRevisionOneEvidenceAndOneSetOfReservedFiles() throws Exception {
        queue(); var claim = service.claim("tenant-a", provider.input.request().id(), provider.clock.now);
        var callback = new SignatureCallbackVerifier.Callback(provider.input, provider.proof());
        var executor = Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            Callable<Void> delivery = () -> { gate.await(); service.receiveCallback(callback, provider.clock.now); return null; };
            var first = executor.submit(delivery); var second = executor.submit(delivery); gate.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            var accepted = stored(); assertThat(accepted.status()).isEqualTo(SignatureOperation.Status.COLLECTING);
            assertThat(accepted.version()).isEqualTo(claim.version() + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_receipt_evidence", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signature_result_file", Integer.class)).isEqualTo(provider.input.request().documents().size());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='SIGNATURE_CALLBACK'", Integer.class)).isEqualTo(1);
            service.finish(claim, new SignatureGateway.Unavailable(SignatureOperation.Failure.TIMEOUT), provider.clock.now);
            assertThat(stored()).isEqualTo(accepted);
        } finally { executor.shutdownNow(); }
    }

    private void queue() {
        var queued = SignatureOperation.queue(provider.input, NOW);
        tx.executeWithoutResult(ignored -> { operations.create(queued); logins.insert(queued, reference); });
    }
    private SignatureOperation stored() { return operations.find("tenant-a", provider.input.request().id()).orElseThrow(); }
    private SignatureOperationService service(SignatureAudit auditService) {
        return proxy(new SignatureOperationService(actors, new JdbcApplicationRepository(jdbc, JSON), operations, logins, evidence, authentication, access, auditService, 15));
    }
    private SignatureAccess.CreateInput command() {
        var request = provider.input.request(); return new SignatureAccess.CreateInput(2, 7, request.authorization().profileKey(), request.authorization().profileVersion(),
                request.documents().stream().map(SignatureRequest.Document::attachmentId).toList(), "合同签署授权", request.authorization().validUntil());
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
}
