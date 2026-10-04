package io.agentflow.signature;

import io.agentflow.storage.LocalDocumentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.agentflow.signature.SignatureOperation.Failure;
import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实回环 HTTP 和真实不可变文件覆盖不重发、严格回执、全正文超时及独立结果保存。
 * @author owlzhangfq@gmail.com
 */
class HttpSignatureGatewayTest {
    @TempDir Path temporary;
    private SignatureHttpFixture fixture;
    @BeforeEach void setup() throws Exception { fixture = new SignatureHttpFixture(temporary); }
    @AfterEach void stop() throws Exception { fixture.close(); }

    @Test void multipartContainsExactFrozenOriginalsAndQueryContainsOnlyTheOriginalIdentity() {
        var gateway = fixture.gateway(); var sent = fixture.sending();
        var observed = (SignatureGateway.Observed) gateway.submit(sent);
        assertThat(observed.verified().receipt().status()).isEqualTo(SignatureReceipt.Status.PENDING);
        assertThat(fixture.verifier.reverify(fixture.input, observed.verified().evidence())).isEqualTo(observed.verified());
        var pending = sent.complete(observed.verified().receipt(), fixture.clock.now); fixture.clock.now = pending.nextAttemptAt();
        var query = pending.claim(fixture.clock.now, Duration.ofSeconds(15));
        var signed = (SignatureGateway.Observed) gateway.query(query);
        assertThat(query.complete(signed.verified().receipt(), fixture.clock.now).status()).isEqualTo(SignatureOperation.Status.COLLECTING);
        assertThat(fixture.requests).extracting(SignatureHttpFixture.Captured::path).containsExactly("/signature/submit", "/signature/query");
        String queryBody = new String(fixture.requests.get(1).body(), StandardCharsets.UTF_8);
        assertThat(queryBody).doesNotContain("明确授权", "filename", "provider-company-1", "documents", "signers");
        fixture.originals.forEach((id, bytes) -> assertThat(queryBody).doesNotContain(new String(bytes, StandardCharsets.UTF_8)));
    }

    @ParameterizedTest @ValueSource(strings = {"DROP", "SLOW_BODY"})
    void unknownSubmissionOnlyQueriesTheSameOperationAndDoesNotResendOriginals(String mode) {
        fixture.mode = SignatureHttpFixture.Mode.valueOf(mode); fixture.declaration.setTimeoutSeconds(1);
        var failure = (SignatureGateway.Unavailable) fixture.gateway().submit(fixture.sending());
        assertThat(failure.failure()).isEqualTo(mode.equals("DROP") ? Failure.CONNECTION : Failure.TIMEOUT);
        assertThat(fixture.calls("submit")).isEqualTo(1);
        fixture.mode = SignatureHttpFixture.Mode.NORMAL;
        var result = (SignatureGateway.Observed) fixture.gateway().query(fixture.querying());
        assertThat(result.verified().receipt().status()).isEqualTo(SignatureReceipt.Status.SIGNED);
        assertThat(result.verified().receipt().operationId()).isEqualTo(fixture.input.request().id());
        assertThat(fixture.calls("submit")).isEqualTo(1); assertThat(fixture.calls("query")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"feature-off", "disabled", "missing-version", "wrong-tenant", "changed-key", "changed-address"})
    void trustedConfigurationFailuresDoNotReadOrTransmitOriginals(String variant) {
        var store = spy(fixture.documents);
        switch (variant) {
            case "feature-off" -> fixture.configuration.setEnabled(false);
            case "disabled" -> fixture.declaration.setEnabled(false);
            case "missing-version" -> fixture.declaration.setVersion(3);
            case "wrong-tenant" -> fixture.configuration.setTenants(Map.of("tenant-b", List.of(fixture.declaration)));
            case "changed-key" -> fixture.declaration.setReceiptPublicKey(profile(keyPair()).receiptPublicKey());
            case "changed-address" -> fixture.declaration.setEndpoint(fixture.declaration.getEndpoint() + "/other");
            default -> throw new AssertionError(variant);
        }
        var gateway = new HttpSignatureGateway(fixture.configuration, JSON, fixture.verifier, store, fixture.clock);
        var failure = (SignatureGateway.Unavailable) gateway.submit(fixture.sending());
        assertThat(failure.failure()).isEqualTo(variant.startsWith("changed-") ? Failure.TARGET_CHANGED : variant.equals("disabled") ? Failure.OPERATION_DISABLED : Failure.NOT_CONFIGURED);
        verify(store, never()).read(any()); assertThat(fixture.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "corrupt", "symlink"})
    void anyMissingCorruptOrLinkedOriginalPreventsTheEntireSubmission(String variant) throws Exception {
        var source = fixture.input.request().documents().get(1); Path file = temporary.resolve(source.contentId() + ".bin");
        if (variant.equals("corrupt")) Files.write(file, new byte[]{1});
        else {
            Files.delete(file);
            if (variant.equals("symlink")) Files.createSymbolicLink(file, temporary.resolve(fixture.input.request().documents().get(0).contentId() + ".bin"));
        }
        assertThat(fixture.gateway().submit(fixture.sending())).isEqualTo(new SignatureGateway.Unavailable(Failure.SOURCE_UNAVAILABLE));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void authorizationAndLeaseAreRecheckedAfterReadingOriginalBytes() {
        var store = spy(fixture.documents);
        doAnswer(call -> { Object bytes = call.callRealMethod(); fixture.clock.now = NOW.plusSeconds(15); return bytes; }).when(store).read(any());
        var gateway = new HttpSignatureGateway(fixture.configuration, JSON, fixture.verifier, store, fixture.clock);
        assertThat(gateway.submit(fixture.sending())).isEqualTo(new SignatureGateway.Unavailable(Failure.LEASE_EXPIRED));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void expiredSendAuthorizationStopsNewBytesButDoesNotStopQueriesForAnIssuedOperation() {
        var original = fixture.input.request(); var auth = original.authorization();
        var shortRequest = new SignatureRequest(original.id(), original.tenantId(), original.source(),
                new SignatureRequest.Authorization(auth.actor(), auth.profileKey(), auth.profileVersion(), auth.profileDigest(), auth.purpose(), auth.authorizedAt(), NOW.plusSeconds(1)), original.documents(), original.signers());
        var claim = SignatureOperation.queue(new SignatureOperation.Input(shortRequest, fixture.input.targetDigest()), NOW).claim(NOW, Duration.ofSeconds(15));
        assertThat(fixture.gateway().submit(claim)).isEqualTo(new SignatureGateway.Unavailable(Failure.AUTHORIZATION_EXPIRED));
        assertThat(fixture.requests).isEmpty();
        var unknown = fixture.sending().unavailable(Failure.CONNECTION, NOW.plusSeconds(2)); fixture.clock.now = NOW.plusSeconds(4000);
        fixture.declaration.setEnabled(false);
        var query = unknown.claim(fixture.clock.now, Duration.ofSeconds(15));
        assertThat(fixture.gateway().query(query)).isInstanceOf(SignatureGateway.Observed.class);
        assertThat(fixture.calls("submit")).isZero(); assertThat(fixture.calls("query")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"UNSIGNED_NOT_FOUND", "AUTHENTICATION", "REDIRECT", "INVALID_SIGNATURE", "FOREIGN_REQUEST", "DUPLICATE_SIGNATURE", "WRONG_CONTENT_TYPE", "ENCODED", "DUPLICATE_CONTENT_TYPE", "OVERSIZED", "EXPIRE_ON_RESPONSE"})
    void transportAndReceiptFailuresNeverBecomeAcceptedProviderFacts(String mode) {
        fixture.mode = SignatureHttpFixture.Mode.valueOf(mode);
        Failure expected = switch (fixture.mode) {
            case UNSIGNED_NOT_FOUND, REDIRECT -> Failure.REMOTE_FAILURE;
            case AUTHENTICATION -> Failure.AUTHENTICATION;
            case OVERSIZED -> Failure.RESPONSE_TOO_LARGE;
            case EXPIRE_ON_RESPONSE -> Failure.LEASE_EXPIRED;
            default -> Failure.INVALID_RESPONSE;
        };
        assertThat(fixture.gateway().query(fixture.querying())).isEqualTo(new SignatureGateway.Unavailable(expected));
        assertThat(fixture.requests).extracting(SignatureHttpFixture.Captured::path).containsExactly("/signature/query");
        assertThat(fixture.calls("submit")).isZero();
    }

    @Test void collectsIndependentVerifiedBytesAndReusesAnUnacknowledgedFileWithoutCurrentConfiguration() throws Exception {
        var claim = fixture.fetching(); var proof = fixture.proof(); var file = claim.artifacts().get(0);
        assertThat(fixture.gateway().collect(claim, proof.evidence(), file)).isEqualTo(new SignatureGateway.Stored(file));
        assertThat(Files.readAllBytes(temporary.resolve(file.contentId() + ".bin"))).containsExactly(fixture.results.get(file.documentId()));
        fixture.configuration.setEnabled(false);
        assertThat(fixture.gateway().collect(claim, proof.evidence(), file)).isEqualTo(new SignatureGateway.Stored(file));
        assertThat(fixture.gateway().collect(claim, proof.evidence(), claim.artifacts().get(1))).isEqualTo(new SignatureGateway.Unavailable(Failure.NOT_CONFIGURED));
        assertThat(fixture.calls("artifact")).isEqualTo(1);
        assertOriginalsAndNoStagingFiles();
    }

    @ParameterizedTest @ValueSource(strings = {"SHORT_FILE", "CORRUPT_FILE", "OVERSIZED", "WRONG_CONTENT_TYPE", "ENCODED", "REDIRECT", "EXPIRE_ON_RESPONSE"})
    void incompleteWrongOrLateResultBytesNeverPublishAFile(String mode) throws Exception {
        fixture.mode = SignatureHttpFixture.Mode.valueOf(mode); var claim = fixture.fetching(); var file = claim.artifacts().get(0);
        Failure expected = switch (fixture.mode) {
            case SHORT_FILE, CORRUPT_FILE -> Failure.ARTIFACT_MISMATCH;
            case OVERSIZED -> Failure.RESPONSE_TOO_LARGE;
            case REDIRECT -> Failure.REMOTE_FAILURE;
            case EXPIRE_ON_RESPONSE -> Failure.LEASE_EXPIRED;
            default -> Failure.INVALID_RESPONSE;
        };
        assertThat(fixture.gateway().collect(claim, fixture.proof().evidence(), file)).isEqualTo(new SignatureGateway.Unavailable(expected));
        assertThat(fixture.calls("artifact")).isEqualTo(1);
        assertThat(temporary.resolve(file.contentId() + ".bin")).doesNotExist(); assertOriginalsAndNoStagingFiles();
    }

    @Test void fileTransferTimeoutCoversTheWholeBodyAfterHeadersArrive() throws Exception {
        fixture.declaration.setTimeoutSeconds(1); fixture.mode = SignatureHttpFixture.Mode.SLOW_BODY; var claim = fixture.fetching(); var file = claim.artifacts().get(0);
        long start = System.nanoTime();
        assertThat(fixture.gateway().collect(claim, fixture.proof().evidence(), file)).isEqualTo(new SignatureGateway.Unavailable(Failure.TIMEOUT));
        assertThat(fixture.calls("artifact")).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(temporary.resolve(file.contentId() + ".bin")).doesNotExist(); assertOriginalsAndNoStagingFiles();
    }

    @Test void retainedProofAndReservedFileMustAgreeBeforeAnyNetworkOrStorageWrite() {
        var claim = fixture.fetching(); var file = claim.artifacts().get(0); var evidence = fixture.proof().evidence();
        var forged = new SignatureReceiptVerifier.Evidence(profile(keyPair()), evidence.targetDigest(), evidence.payloadBase64(), evidence.signatureBase64(), evidence.verifiedAt());
        assertThat(fixture.gateway().collect(claim, forged, file)).isEqualTo(new SignatureGateway.Unavailable(Failure.INVALID_RESPONSE));
        var replacement = new SignatureOperation.StoredArtifact(file.documentId(), java.util.UUID.randomUUID(), file.size(), file.sha256());
        assertThat(fixture.gateway().collect(claim, evidence, replacement)).isEqualTo(new SignatureGateway.Unavailable(Failure.ARTIFACT_MISMATCH));
        assertThat(fixture.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"corrupt", "symlink"})
    void anExistingCorruptResultIsNotOverwrittenAndStorageLimitsFailBeforeDownloading(String variant) throws Exception {
        var claim = fixture.fetching(); var file = claim.artifacts().get(0); var proof = fixture.proof().evidence();
        Path target = temporary.resolve(file.contentId() + ".bin");
        if (variant.equals("corrupt")) Files.write(target, new byte[]{42});
        else Files.createSymbolicLink(target, temporary.resolve(fixture.input.request().documents().get(0).contentId() + ".bin"));
        assertThat(fixture.gateway().collect(claim, proof, file)).isEqualTo(new SignatureGateway.Unavailable(Failure.ARTIFACT_MISMATCH));
        if (variant.equals("corrupt")) assertThat(Files.readAllBytes(target)).containsExactly((byte) 42);
        else assertThat(Files.isSymbolicLink(target)).isTrue();
        var small = new LocalDocumentStore(temporary.toString(), 1);
        var gateway = new HttpSignatureGateway(fixture.configuration, JSON, fixture.verifier, small, fixture.clock);
        assertThat(gateway.collect(claim, proof, claim.artifacts().get(1))).isEqualTo(new SignatureGateway.Unavailable(Failure.STORAGE_UNAVAILABLE));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void networkAndFileWorkCannotRunInsideDatabaseTransactionsOrFromUnclaimedStates() {
        var gateway = fixture.gateway();
        assertThatThrownBy(() -> gateway.submit(SignatureOperation.queue(fixture.input, NOW))).isInstanceOf(IllegalStateException.class);
        var query = fixture.querying(); var fetch = fixture.fetching();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.submit(fixture.sending())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.query(query)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.collect(fetch, fixture.proof().evidence(), fetch.artifacts().get(0))).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(fixture.requests).isEmpty();
    }

    @Test void httpClientCannotResubscribeAndReplayTheSubmissionBody() throws Exception {
        byte[] bytes = "fixed original bytes".getBytes(StandardCharsets.UTF_8);
        var body = new HttpSignatureGateway.OnceBodyPublisher(HttpRequest.BodyPublishers.ofByteArray(bytes));
        var first = HttpResponse.BodySubscribers.ofByteArray(); body.subscribe(requestSubscriber(first));
        assertThat(first.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS)).containsExactly(bytes);
        var second = HttpResponse.BodySubscribers.ofByteArray(); body.subscribe(requestSubscriber(second));
        assertThatThrownBy(() -> second.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
    }

    private void assertOriginalsAndNoStagingFiles() throws Exception {
        for (var entry : fixture.originals.entrySet()) assertThat(Files.readAllBytes(temporary.resolve(entry.getKey() + ".bin"))).containsExactly(entry.getValue());
        try (var files = Files.list(temporary)) { assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".part")).toList()).isEmpty(); }
    }

    private java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> requestSubscriber(HttpResponse.BodySubscriber<byte[]> response) {
        return new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { response.onSubscribe(subscription); }
            @Override public void onNext(java.nio.ByteBuffer value) { response.onNext(List.of(value)); }
            @Override public void onError(Throwable failure) { response.onError(failure); }
            @Override public void onComplete() { response.onComplete(); }
        };
    }
}
