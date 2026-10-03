package io.agentflow.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static io.agentflow.event.EventTestRequests.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 签名字节、时间窗、严格信封、来源绑定与配置轮换的接入边界。
 * @author owlzhangfq@gmail.com
 */
class EventIngressVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String BODY = "{\"envelopeVersion\":1,\"tenantId\":\"demo\",\"sourceKey\":\"erp\",\"eventType\":\"GoodsAccepted\",\"applicationId\":\"09ce297b-0811-4015-a135-eae7ed1cc2d1\",\"roundNo\":1,\"waitId\":\"wait-1\",\"contractKey\":\"accepted\",\"contractVersion\":1}";
    private final EventIngressVerifier verifier = verifier(configuration());

    @Test void repeatedDeliveryTimeDoesNotChangeTheAuthenticatedIdentity() throws Exception {
        var first = verifier.verify(signed(BODY, "evt-1", NOW), NOW);
        assertThat(verifier.verify(signed(BODY, "evt-1", NOW.plusSeconds(30)), NOW.plusSeconds(30))).isEqualTo(first);
        assertThat(first.trustRevision()).isEqualTo(1);
        assertThat(verifier.availability(first)).isEqualTo(EventIngressVerifier.Availability.AVAILABLE);
    }
    @Test void changedRawWhitespaceEventIdAndTimestampInvalidateTheSignature() {
        var body = signed(BODY, "evt-1", NOW); body.setContent((BODY + " ").getBytes(StandardCharsets.UTF_8));
        var event = signed(BODY, "evt-1", NOW); event.removeHeader("webhook-id"); event.addHeader("webhook-id", "evt-2");
        var timestamp = signed(BODY, "evt-1", NOW); timestamp.removeHeader("webhook-timestamp"); timestamp.addHeader("webhook-timestamp", Long.toString(NOW.plusSeconds(1).getEpochSecond()));
        denied(body, "EVENT_UNAUTHENTICATED"); denied(event, "EVENT_UNAUTHENTICATED"); denied(timestamp, "EVENT_UNAUTHENTICATED");
    }
    @Test void timestampWindowHasExplicitBoundaries() throws Exception {
        denied(signed(BODY, "evt-1", NOW.minusSeconds(301)), "EVENT_UNAUTHENTICATED");
        denied(signed(BODY, "evt-1", NOW.plusSeconds(301)), "EVENT_UNAUTHENTICATED");
        assertThat(verifier.verify(signed(BODY, "evt-1", NOW.minusSeconds(300)), NOW)).isNotNull();
        assertThat(verifier.verify(signed(BODY, "evt-1", NOW.plusSeconds(300)), NOW)).isNotNull();
    }
    @Test void tenantAndSourceInSignedBodyCannotBeReboundByRoutingHeaders() {
        for (String body : List.of(BODY.replace("\"demo\"", "\"foreign\""), BODY.replace("\"erp\"", "\"warehouse\""))) {
            denied(signed(body, "evt-1", NOW), "EVENT_INPUT_INVALID");
        }
    }
    @Test void unknownSourcesAndDuplicateHeadersFailBeforeParsing() {
        var unknown = signed(BODY, "evt-1", NOW); unknown.removeHeader("webhook-source"); unknown.addHeader("webhook-source", "unknown");
        denied(unknown, "EVENT_UNAUTHENTICATED");
        for (String name : List.of("webhook-tenant", "webhook-source", "webhook-id", "webhook-timestamp", "webhook-signature")) {
            var request = signed(BODY, "evt-1", NOW); request.addHeader(name, request.getHeader(name)); denied(request, "EVENT_UNAUTHENTICATED");
        }
        // MockHttpServletRequest.addHeader 会替换 Content-Type，显式模拟真实重复头枚举。
        var duplicateType = new MockHttpServletRequest() {
            @Override public java.util.Enumeration<String> getHeaders(String name) {
                return name.equalsIgnoreCase("Content-Type") ? java.util.Collections.enumeration(List.of("application/json", "application/json")) : super.getHeaders(name);
            }
        };
        var original = signed(BODY, "evt-1", NOW);
        java.util.Collections.list(original.getHeaderNames()).forEach(name -> duplicateType.addHeader(name, original.getHeader(name)));
        duplicateType.setContent(original.getContentAsByteArray()); denied(duplicateType, "EVENT_UNAUTHENTICATED");
    }
    @Test void signaturePrecedesBusinessParsingAndUnknownFieldsCannotCarryVariables() {
        var malformed = signed("not JSON", "evt-1", NOW); malformed.setContent("another".getBytes(StandardCharsets.UTF_8));
        denied(malformed, "EVENT_UNAUTHENTICATED"); denied(signed("not JSON", "evt-1", NOW), "EVENT_INPUT_INVALID");
        for (String body : List.of(BODY + " {}", "null", BODY.replace("\"roundNo\":1", "\"roundNo\":1,\"roundNo\":2"),
                BODY.replace("\"roundNo\":1", "\"roundNo\":\"1\""), BODY.replace("\"roundNo\":1", "\"roundNo\":1.5"),
                BODY.replace("\"envelopeVersion\":1", "\"envelopeVersion\":null"), BODY.replace("\"contractVersion\":1", "\"contractVersion\":0"),
                BODY.replace("\"contractVersion\":1", "\"contractVersion\":1,\"variables\":{\"approved\":true}"))) {
            denied(signed(body, "evt-1", NOW), "EVENT_INPUT_INVALID");
        }
    }
    @Test void rejectsNonUtf8CompressionAndOversizeIncludingUnknownLength() {
        denied(signed(new byte[]{(byte) 0xff}, "evt-1", NOW), "EVENT_INPUT_INVALID");
        var compressed = signed(BODY, "evt-1", NOW); compressed.addHeader("Content-Encoding", "gzip"); denied(compressed, "EVENT_INPUT_INVALID");
        var charset = signed(BODY, "evt-1", NOW); charset.setContentType("application/json;charset=ISO-8859-1"); denied(charset, "EVENT_INPUT_INVALID");
        denied(signed("x".repeat(EventIngressVerifier.MAX_BODY_BYTES + 1), "evt-1", NOW), "EVENT_BODY_TOO_LARGE");
        var unknown = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return -1; } };
        var source = signed("x".repeat(EventIngressVerifier.MAX_BODY_BYTES + 1), "evt-1", NOW);
        java.util.Collections.list(source.getHeaderNames()).forEach(name -> unknown.addHeader(name, source.getHeader(name)));
        unknown.setContent(source.getContentAsByteArray()); denied(unknown, "EVENT_BODY_TOO_LARGE");
    }
    @Test void rotationCanKeepTrustButNewTrustRevisionIsQuarantined() throws Exception {
        byte[] old = "previous-event-key-0123456789-abc!".getBytes(StandardCharsets.UTF_8);
        var config = configuration(); config.setSources(Map.of("erp", new EventIngressConfiguration.Source("demo", "erp", 1, true,
                List.of(SECRET, "whsec_" + Base64.getEncoder().encodeToString(old)))));
        var rotated = verifier(config); var request = signed(BODY, "evt-1", NOW); request.removeHeader("webhook-signature");
        request.addHeader("webhook-signature", "v2,AAAA " + sign(old, "evt-1", Long.toString(NOW.getEpochSecond()), BODY.getBytes(StandardCharsets.UTF_8)));
        var input = verifier.verify(signed(BODY, "evt-1", NOW), NOW);
        assertThat(rotated.verify(request, NOW)).isEqualTo(input); denied(request, "EVENT_UNAUTHENTICATED");
        config.setSources(Map.of("erp", new EventIngressConfiguration.Source("demo", "erp", 2, true, List.of(SECRET))));
        assertThat(verifier(config).availability(input)).isEqualTo(EventIngressVerifier.Availability.CHANGED);
        config.setSources(Map.of("erp", new EventIngressConfiguration.Source("demo", "erp", 1, false, List.of())));
        assertThat(verifier(config).availability(input)).isEqualTo(EventIngressVerifier.Availability.DISABLED);
        config.setEnabled(false); assertThat(verifier(config).availability(input)).isEqualTo(EventIngressVerifier.Availability.DISABLED);
    }
    @Test void malformedConfigurationFailsWithNoCredentialInItsMessage() {
        for (var source : List.of(new EventIngressConfiguration.Source("demo", "erp", 0, true, List.of(SECRET)),
                new EventIngressConfiguration.Source("demo", "erp", 1, true, List.of("private-invalid-secret")),
                new EventIngressConfiguration.Source("demo", "erp", 1, true, List.of()))) {
            var config = configuration(); config.setSources(Map.of("source", source));
            assertThatThrownBy(() -> verifier(config)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid event ingress configuration");
        }
        var config = configuration(); var source = new EventIngressConfiguration.Source("demo", "erp", 1, true, List.of(SECRET));
        config.setSources(Map.of("a", source, "b", source)); assertThatThrownBy(() -> verifier(config)).isInstanceOf(IllegalStateException.class);
        assertThat(source.toString()).doesNotContain(SECRET);
    }
    @Test void onlyExactPostIsSignedAndQueryOverridesAreRejected() {
        var request = signed(BODY, "evt-1", NOW); assertThat(EventIngressVerifier.matches(request)).isTrue();
        request.setQueryString("tenant=other"); denied(request, "EVENT_INPUT_INVALID"); request.setQueryString(null);
        request.setMethod("GET"); assertThat(EventIngressVerifier.matches(request)).isFalse();
        request.setMethod("POST"); request.setRequestURI(EventIngressVerifier.PATH + "/id/retry"); assertThat(EventIngressVerifier.matches(request)).isFalse();
    }
    @Test void signingDelimiterIsForbiddenInTheEventId() {
        denied(signed(BODY, "evt.with-dot", NOW), "EVENT_UNAUTHENTICATED");
    }
    @Test void distinctSourcesCannotShareSigningCredentials() {
        var config = configuration();
        config.setSources(Map.of("erp", new EventIngressConfiguration.Source("demo", "erp", 1, true, List.of(SECRET)),
                "warehouse", new EventIngressConfiguration.Source("demo", "warehouse", 1, true, List.of(SECRET))));
        assertThatThrownBy(() -> verifier(config)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid event ingress configuration");
    }
    @Test void anAdditionalUnsupportedSchemeDoesNotInvalidateAValidV1Signature() throws Exception {
        var request = signed(BODY, "evt-1", NOW); String signature = request.getHeader("webhook-signature");
        request.removeHeader("webhook-signature"); request.addHeader("webhook-signature", "v1a,AAAA " + signature);
        assertThat(verifier.verify(request, NOW).eventId()).isEqualTo("evt-1");
    }
    private void denied(MockHttpServletRequest request, String code) {
        assertThatThrownBy(() -> verifier.verify(request, NOW)).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
    private static EventIngressVerifier verifier(EventIngressConfiguration config) { return new EventIngressVerifier(config, new ObjectMapper().findAndRegisterModules()); }
    private static EventIngressConfiguration configuration() {
        var config = new EventIngressConfiguration(); config.setEnabled(true);
        config.setSources(Map.of("erp", new EventIngressConfiguration.Source("demo", "erp", 1, true, List.of(SECRET))));
        return config;
    }
}
