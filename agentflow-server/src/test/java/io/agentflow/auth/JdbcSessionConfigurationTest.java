package io.agentflow.auth;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 共享会话的显式开关、超时与 Cookie 属性不因持久化而放宽。
 * @author owlzhangfq@gmail.com
 */
class JdbcSessionConfigurationTest {
    private final JdbcSessionConfiguration configuration = new JdbcSessionConfiguration();

    @Test
    void rejectsNonEnterpriseModeAndInvalidTimeouts() {
        var server = new ServerProperties();
        var disabled = new OidcProperties(false, null, null, null, null, null, Map.of(), Map.of(), false);
        var enabled = new OidcProperties(true, null, null, null, null, null, Map.of(), Map.of(), false);
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.sessionRepositorySettings(disabled, server, false));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.sessionRepositorySettings(enabled, server, true));
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500),
                Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds((long) Integer.MAX_VALUE + 1)}) {
            server.getServlet().getSession().setTimeout(invalid);
            assertThatIllegalArgumentException().isThrownBy(() -> configuration.sessionRepositorySettings(enabled, server, false));
        }
    }

    @Test
    void cookieSecurityUsesConfiguredOriginAndCannotBeOverriddenByProxyHeaders() {
        var request = new MockHttpServletRequest();
        request.setServerName("attacker.invalid");
        request.addHeader("X-Forwarded-Proto", "http");
        var response = new MockHttpServletResponse();
        configuration.sharedSessionCookie("https://flow.example").writeCookieValue(
                new CookieSerializer.CookieValue(request, response, "test-session"));
        assertThat(response.getHeader("Set-Cookie")).startsWith("AGENTFLOW_SESSION=test-session;")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax").doesNotContain("Domain=");
    }
}
