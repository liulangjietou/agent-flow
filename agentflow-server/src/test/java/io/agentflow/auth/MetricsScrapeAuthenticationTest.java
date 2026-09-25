package io.agentflow.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采集入口在未启用或凭证异常时保持关闭，异常信息不泄漏文件内容。
 * @author owlzhangfq@gmail.com
 */
class MetricsScrapeAuthenticationTest {
    private static final String TOKEN = "metrics-test-only-abcdefghijklmnopqrstuvwxyz-0123456789";

    @Test
    void disabledDoesNotReadASecretOrAcceptAnyCredential() {
        var gate = new MetricsScrapeAuthentication(false, "/missing/private-file");
        var response = new MockHttpServletResponse();
        assertThat(gate.authorize(request(TOKEN), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsEmptyMissingOversizedAndMalformedFilesWithoutLeakingCause() throws Exception {
        assertInvalid("/missing/private-file");
        Path file = Files.createTempFile(Path.of("/fyoung/tmp"), "metrics-invalid-", ".txt");
        try {
            for (String value : new String[]{"", "short", "x".repeat(257), TOKEN + "\n\n", TOKEN + " private-content"}) {
                Files.writeString(file, value);
                assertInvalid(file.toString());
            }
        } finally { Files.delete(file); }
    }

    @Test
    void rejectsDuplicateOrOversizedHeadersAndLimitsPrivilegeToOnePath() throws Exception {
        Path file = Files.createTempFile(Path.of("/fyoung/tmp"), "metrics-valid-", ".txt");
        try {
            Files.writeString(file, TOKEN + "\r\n");
            var gate = new MetricsScrapeAuthentication(true, file.toString());
            var duplicate = request(TOKEN);
            duplicate.addHeader("Authorization", "Bearer " + TOKEN);
            assertThat(gate.authorize(duplicate, new MockHttpServletResponse())).isFalse();
            assertThat(gate.authorize(request("x".repeat(300)), new MockHttpServletResponse())).isFalse();
            assertThat(gate.authorize(request(TOKEN), new MockHttpServletResponse())).isTrue();
            assertThat(gate.matches(request(TOKEN))).isTrue();
            for (String path : new String[]{"/api/v1/applications", "/actuator/prometheus/extra", "/actuator/env"}) {
                assertThat(gate.matches(new MockHttpServletRequest("GET", path))).isFalse();
            }
        } finally { Files.delete(file); }
    }

    private static MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static void assertInvalid(String file) {
        assertThatThrownBy(() -> new MetricsScrapeAuthentication(true, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid metrics token file configuration").hasNoCause();
    }
}
