package io.agentflow.finance.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static io.agentflow.finance.callback.PaymentCallbackTestRequests.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 原始字节、密钥轮换、时间窗、解析次序和身份绑定的接入边界。
 * @author owlzhangfq@gmail.com
 */
class PaymentCallbackVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final String BODY = "{\"contractVersion\":1,\"type\":\"payment.changed\",\"tenantId\":\"demo\",\"kind\":\"EMPLOYEE\",\"authorizationId\":\"09ce297b-0811-4015-a135-eae7ed1cc2d1\",\"commandDigest\":\"" + "a".repeat(64) + "\",\"sourceRevision\":3}";
    private final FinanceGatewayConfiguration gateway = gateway();
    private final PaymentCallbackConfiguration configuration = configuration();
    private final PaymentCallbackVerifier verifier = verifier(configuration);

    @Test void acceptsAuthenticatedRawBodyAndRetriedTimestampKeepsSameImmutableIdentity() throws Exception {
        var first = verifier.verify(signed(BODY, NOW), NOW);
        var retried = verifier.verify(signed(BODY, NOW.plusSeconds(30)), NOW.plusSeconds(30));
        assertThat(retried).isEqualTo(first); assertThat(first.signal().sourceRevision()).isEqualTo(3);
        assertThat(first.targetDigest()).isEqualTo(gateway.destination("demo").orElseThrow().digest("demo"));
    }
    @Test void rawWhitespaceAndChangedEventIdAreCoveredBySignature() {
        var whitespace = signed(BODY, NOW); whitespace.setContent((BODY + " ").getBytes(StandardCharsets.UTF_8));
        var event = signed(BODY, NOW); event.removeHeader("webhook-id"); event.addHeader("webhook-id", "evt_other");
        denied(whitespace, "PAYMENT_CALLBACK_UNAUTHENTICATED"); denied(event, "PAYMENT_CALLBACK_UNAUTHENTICATED");
    }
    @Test void rejectsExpiredOrFutureDeliveryAndAcceptsExactWindowBoundary() throws Exception {
        denied(signed(BODY, NOW.minusSeconds(301)), "PAYMENT_CALLBACK_UNAUTHENTICATED");
        denied(signed(BODY, NOW.plusSeconds(301)), "PAYMENT_CALLBACK_UNAUTHENTICATED");
        assertThat(verifier.verify(signed(BODY, NOW.minusSeconds(300)), NOW)).isNotNull();
        assertThat(verifier.verify(signed(BODY, NOW.plusSeconds(300)), NOW)).isNotNull();
    }
    @Test void rotatedKeysAndMultipleSignaturesKeepOneEventIdentity() throws Exception {
        byte[] old = "previous-callback-key-0123456789!x".getBytes(StandardCharsets.UTF_8);
        configuration.setTenants(Map.of("demo", new PaymentCallbackConfiguration.Tenant(List.of(SECRET, "whsec_" + Base64.getEncoder().encodeToString(old)))));
        var rotated = verifier(configuration); var request = signed(BODY, NOW);
        request.removeHeader("webhook-signature"); request.addHeader("webhook-signature", "v2,AAAA " + sign(old, "evt_1", Long.toString(NOW.getEpochSecond()), BODY.getBytes(StandardCharsets.UTF_8)));
        assertThat(rotated.verify(request, NOW)).isEqualTo(verifier.verify(signed(BODY, NOW), NOW));
        denied(request, "PAYMENT_CALLBACK_UNAUTHENTICATED");
    }
    @Test void rejectsUnknownTenantOrDuplicateHeaderBeforeParsing() {
        var tenant = signed(BODY, NOW); tenant.removeHeader("webhook-tenant"); tenant.addHeader("webhook-tenant", "foreign");
        var duplicate = signed(BODY, NOW); duplicate.addHeader("webhook-id", "evt_1");
        denied(tenant, "PAYMENT_CALLBACK_UNAUTHENTICATED"); denied(duplicate, "PAYMENT_CALLBACK_UNAUTHENTICATED");
    }
    @Test void signatureIsCheckedBeforeMalformedJsonIsRead() {
        var invalid = signed("not json", NOW); invalid.setContent("different".getBytes(StandardCharsets.UTF_8));
        denied(invalid, "PAYMENT_CALLBACK_UNAUTHENTICATED"); denied(signed("not json", NOW), "PAYMENT_CALLBACK_INVALID");
    }
    @Test void authenticatedJsonRejectsExtraFieldsDuplicatesTrailingDataCoercionAndWrongIdentity() {
        for (String body : List.of(BODY.replace("\"sourceRevision\":3", "\"sourceRevision\":3,\"sourceRevision\":4"), BODY + " {}",
                BODY.replace("\"sourceRevision\":3", "\"sourceRevision\":\"3\""), BODY.replace("\"sourceRevision\":3", "\"sourceRevision\":3.1"),
                BODY.replace("\"contractVersion\":1", "\"contractVersion\":null"), BODY.replace("\"demo\"", "\"foreign\""),
                BODY.replace("\"EMPLOYEE\"", "0"), BODY.replace("\"sourceRevision\":3", "\"sourceRevision\":3,\"paidAmount\":100"),
                BODY.replace("payment.changed", "payment.approved"), BODY.replace("\"sourceRevision\":3", "\"sourceRevision\":0"), "null")) {
            denied(signed(body, NOW), "PAYMENT_CALLBACK_INVALID");
        }
    }
    @Test void rejectsNonUtf8CompressedOrOversizedBodyIncludingMissingContentLength() {
        var wrongCharset = signed(BODY, NOW); wrongCharset.setContentType("application/json;charset=ISO-8859-1"); denied(wrongCharset, "PAYMENT_CALLBACK_INVALID");
        var compressed = signed(BODY, NOW); compressed.addHeader("Content-Encoding", "gzip"); denied(compressed, "PAYMENT_CALLBACK_INVALID");
        var invalidUtf8 = signed(BODY, NOW); byte[] bytes = {(byte) 0xff}; invalidUtf8.setContent(bytes);
        invalidUtf8.removeHeader("webhook-signature"); invalidUtf8.addHeader("webhook-signature", sign(KEY, "evt_1", Long.toString(NOW.getEpochSecond()), bytes));
        denied(invalidUtf8, "PAYMENT_CALLBACK_INVALID");
        denied(signed("x".repeat(PaymentCallbackVerifier.MAX_BODY_BYTES + 1), NOW), "PAYMENT_CALLBACK_TOO_LARGE");
        var unknownLength = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return -1; } };
        var original = signed("x".repeat(PaymentCallbackVerifier.MAX_BODY_BYTES + 1), NOW);
        original.getHeaderNames().asIterator().forEachRemaining(name -> unknownLength.addHeader(name, original.getHeader(name)));
        unknownLength.setContent(original.getContentAsByteArray()); denied(unknownLength, "PAYMENT_CALLBACK_TOO_LARGE");
    }
    @Test void disabledOrInvalidConfigurationFailsClosedWithoutSecretText() {
        configuration.setEnabled(false);
        assertThatThrownBy(() -> verifier(configuration).verify(signed(BODY, NOW), NOW)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("PAYMENT_CALLBACK_DISABLED"));
        configuration.setEnabled(true); configuration.setTenants(Map.of("demo", new PaymentCallbackConfiguration.Tenant(List.of("whsec_secret-invalid"))));
        assertThatThrownBy(() -> verifier(configuration)).hasMessage("Invalid payment callback configuration").hasNoCause();
        assertThat(configuration.getTenants().get("demo").toString()).doesNotContain("secret-invalid");
    }
    @Test void authBypassMatchesOnlyExactCallbackPost() {
        var request = new MockHttpServletRequest("POST", PaymentCallbackVerifier.PATH); assertThat(PaymentCallbackVerifier.matches(request)).isTrue();
        request.setMethod("GET"); assertThat(PaymentCallbackVerifier.matches(request)).isFalse();
        request.setMethod("POST"); request.setRequestURI(PaymentCallbackVerifier.PATH + "/id/retry"); assertThat(PaymentCallbackVerifier.matches(request)).isFalse();
    }
    private PaymentCallbackVerifier verifier(PaymentCallbackConfiguration value) { return new PaymentCallbackVerifier(value, gateway, new ObjectMapper().findAndRegisterModules()); }
    private void denied(MockHttpServletRequest request, String code) {
        assertThatThrownBy(() -> verifier.verify(request, NOW)).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    private static MockHttpServletRequest signed(String body, Instant delivered) {
        var request = new MockHttpServletRequest("POST", PaymentCallbackVerifier.PATH); byte[] bytes = body.getBytes(StandardCharsets.UTF_8); String time = Long.toString(delivered.getEpochSecond());
        request.setContentType("application/json"); request.setContent(bytes); request.addHeader("webhook-tenant", "demo"); request.addHeader("webhook-id", "evt_1");
        request.addHeader("webhook-timestamp", time); request.addHeader("webhook-signature", sign(KEY, "evt_1", time, bytes)); return request;
    }
    private static PaymentCallbackConfiguration configuration() {
        var configuration = new PaymentCallbackConfiguration(); configuration.setEnabled(true);
        configuration.setTenants(Map.of("demo", new PaymentCallbackConfiguration.Tenant(List.of(SECRET)))); return configuration;
    }
    private static FinanceGatewayConfiguration gateway() {
        var configuration = new FinanceGatewayConfiguration(); var target = new FinanceGatewayConfiguration.Target(); target.setEndpoint("http://127.0.0.1:12345/finance");
        target.setAllowUnauthenticatedLoopback(true); configuration.setEnabled(true); configuration.setTenants(Map.of("demo", target)); return configuration;
    }
}
