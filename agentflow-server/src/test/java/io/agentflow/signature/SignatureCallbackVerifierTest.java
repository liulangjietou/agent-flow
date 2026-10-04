package io.agentflow.signature;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 回调入口只信任固定资料签名，有界读取并严格区分无效认证和证据存储故障。
 * @author owlzhangfq@gmail.com
 */
class SignatureCallbackVerifierTest {
    private final java.security.KeyPair key = keyPair();
    private final SignatureProfile profile = profile(key);
    private final SignatureOperation.Input input = input(profile);
    private final SignatureOperation operation = SignatureOperation.queue(input, NOW);
    private final SignatureReceipt receipt = receipt(input, SignatureReceipt.Status.SIGNED);
    private final byte[] payload = body(profile, input, receipt);
    private final JdbcSignatureOperationRepository operations = mock(JdbcSignatureOperationRepository.class);
    private final JdbcSignatureEvidenceRepository evidence = mock(JdbcSignatureEvidenceRepository.class);
    private final SignatureGatewayConfiguration configuration = mock(SignatureGatewayConfiguration.class);
    private final SignatureReceiptVerifier receipts = new SignatureReceiptVerifier(MAPPER);
    private final SignatureCallbackVerifier verifier = new SignatureCallbackVerifier(operations, evidence, configuration, receipts);

    @BeforeEach void configure() {
        when(operations.find(profile.tenantId(), input.request().id())).thenReturn(Optional.of(operation));
        when(configuration.find(profile.tenantId(), profile.key(), profile.version()))
                .thenReturn(Optional.of(new SignatureGatewayConfiguration.Declaration(profile, false, null)));
    }

    @Test void originalSignatureRemainsValidWhenNewAuthorizationForProfileIsDisabled() throws Exception {
        var callback = verifier.verify(request(), NOW.plusSeconds(2));
        assertThat(callback.input()).isEqualTo(input); assertThat(callback.verified().receipt()).isEqualTo(receipt);
        assertThat(callback.verified().evidence().profile()).isEqualTo(profile);
        assertThat(callback.toString()).doesNotContain(profile.receiptPublicKey(), receipt.providerReference());
        verifyNoInteractions(evidence);
    }

    @Test void previouslyVerifiedEvidenceKeepsOriginalKeyAfterProfileRemovalOrReplacement() throws Exception {
        var accepted = operation.claim(NOW, Duration.ofSeconds(30)).complete(receipt, NOW.plusSeconds(1));
        var proof = receipts.verify(input, profile, payload, sign(key, payload), NOW.plusSeconds(1));
        when(operations.find(profile.tenantId(), input.request().id())).thenReturn(Optional.of(accepted));
        when(evidence.forReceipt(accepted)).thenReturn(proof);
        when(configuration.find(profile.tenantId(), profile.key(), profile.version())).thenReturn(Optional.empty());
        assertThat(verifier.verify(request(), NOW.plusSeconds(2)).verified().receipt()).isEqualTo(receipt);
        when(configuration.find(profile.tenantId(), profile.key(), profile.version()))
                .thenReturn(Optional.of(new SignatureGatewayConfiguration.Declaration(profile(keyPair()), true, null)));
        assertThat(verifier.verify(request(), NOW.plusSeconds(2)).verified().receipt()).isEqualTo(receipt);
        verify(evidence, times(2)).forReceipt(accepted);
    }

    @Test void exactPostIsTheOnlyPublicMatchAndQueryStringCannotAlterCallbackMeaning() {
        assertThat(SignatureCallbackVerifier.matches(request())).isTrue();
        for (String method : java.util.List.of("GET", "PUT", "DELETE", "OPTIONS")) {
            var request = request(); request.setMethod(method); assertThat(SignatureCallbackVerifier.matches(request)).isFalse();
            rejected(request, "INVALID_SIGNATURE_CALLBACK");
        }
        for (String suffix : java.util.List.of("/", "/retry", "/anything")) {
            var request = request(); request.setRequestURI(SignatureCallbackVerifier.PATH + suffix);
            assertThat(SignatureCallbackVerifier.matches(request)).isFalse(); rejected(request, "INVALID_SIGNATURE_CALLBACK");
        }
        var query = request(); query.setQueryString(""); rejected(query, "INVALID_SIGNATURE_CALLBACK");
        verifyNoInteractions(operations);
    }

    @Test void duplicateMissingOrAmbiguousAuthenticationHeadersCannotReachOperationLookup() {
        for (String header : java.util.List.of(SignatureCallbackVerifier.TENANT_HEADER, SignatureCallbackVerifier.OPERATION_HEADER,
                HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, "Content-Type")) {
            var duplicate = spy(request()); String value = duplicate.getHeader(header);
            // MockHttpServletRequest 会覆盖 Content-Type，显式提供 Servlet 容器能够返回的重复头枚举。
            doAnswer(ignored -> java.util.Collections.enumeration(java.util.List.of(value, value))).when(duplicate).getHeaders(header);
            assertThat(java.util.Collections.list(duplicate.getHeaders(header))).hasSize(2);
            rejected(duplicate, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        }
        var missing = request(); missing.removeHeader(SignatureCallbackVerifier.OPERATION_HEADER); rejected(missing, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        var nonCanonical = request(); nonCanonical.removeHeader(SignatureCallbackVerifier.OPERATION_HEADER);
        nonCanonical.addHeader(SignatureCallbackVerifier.OPERATION_HEADER, input.request().id().toString().toUpperCase(java.util.Locale.ROOT)); rejected(nonCanonical, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        verifyNoInteractions(operations);
    }

    @Test void onlyUncompressedUtf8JsonIsAccepted() {
        for (String type : java.util.List.of("text/plain", "application/json;charset=ISO-8859-1", "application/problem+json")) {
            var request = request(); request.setContentType(type); rejected(request, "INVALID_SIGNATURE_CALLBACK");
        }
        var compressed = request(); compressed.addHeader("Content-Encoding", "gzip"); rejected(compressed, "INVALID_SIGNATURE_CALLBACK");
        verifyNoInteractions(operations);
    }

    @Test void knownAndUnknownLengthBodiesAreBoundedBeforeDatabaseOrCryptoWork() {
        var known = request(); known.setContent(new byte[SignatureReceiptVerifier.MAX_RECEIPT_BYTES + 1]); rejected(known, "SIGNATURE_CALLBACK_TOO_LARGE");
        var unknown = spy(request()); unknown.setContent(new byte[SignatureReceiptVerifier.MAX_RECEIPT_BYTES + 1]);
        doReturn(-1L).when(unknown).getContentLengthLong(); rejected(unknown, "SIGNATURE_CALLBACK_TOO_LARGE");
        verifyNoInteractions(operations);
    }

    @Test void wrongKeyModifiedBodyUnknownOperationAndCrossTenantAllFailAuthentication() {
        var wrong = request(); wrong.removeHeader(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER);
        wrong.addHeader(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(keyPair(), payload)); rejected(wrong, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        var changed = request(); changed.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); rejected(changed, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        var unknown = request(); unknown.removeHeader(SignatureCallbackVerifier.OPERATION_HEADER);
        unknown.addHeader(SignatureCallbackVerifier.OPERATION_HEADER, UUID.randomUUID().toString()); rejected(unknown, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        var crossTenant = request(); crossTenant.removeHeader(SignatureCallbackVerifier.TENANT_HEADER); crossTenant.addHeader(SignatureCallbackVerifier.TENANT_HEADER, "tenant-b");
        rejected(crossTenant, "SIGNATURE_CALLBACK_UNAUTHENTICATED");
        when(configuration.find(profile.tenantId(), profile.key(), profile.version())).thenReturn(Optional.empty());
        rejected(request(), "SIGNATURE_CALLBACK_UNAUTHENTICATED");
    }

    @Test void evidenceCorruptionAndDatabaseOutageAreNotMisreportedAsAnInvalidSender() {
        var accepted = operation.claim(NOW, Duration.ofSeconds(30)).complete(receipt, NOW.plusSeconds(1));
        when(operations.find(profile.tenantId(), input.request().id())).thenReturn(Optional.of(accepted));
        when(configuration.find(profile.tenantId(), profile.key(), profile.version())).thenReturn(Optional.empty());
        when(evidence.forReceipt(accepted)).thenThrow(new DomainException("SIGNATURE_EVIDENCE_CORRUPT", "Stored evidence is corrupt"));
        rejected(request(), "SIGNATURE_EVIDENCE_CORRUPT");
        when(operations.find(profile.tenantId(), input.request().id())).thenThrow(new DataAccessResourceFailureException("Database unavailable"));
        assertThatThrownBy(() -> verifier.verify(request(), NOW.plusSeconds(2))).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", SignatureCallbackVerifier.PATH);
        request.addHeader(SignatureCallbackVerifier.TENANT_HEADER, profile.tenantId());
        request.addHeader(SignatureCallbackVerifier.OPERATION_HEADER, input.request().id().toString());
        request.addHeader(HttpSignatureGateway.RECEIPT_SIGNATURE_HEADER, sign(key, payload));
        request.setContentType("application/json;charset=UTF-8"); request.setContent(payload); return request;
    }
    private void rejected(MockHttpServletRequest request, String code) {
        assertThatThrownBy(() -> verifier.verify(request, NOW.plusSeconds(2))).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
