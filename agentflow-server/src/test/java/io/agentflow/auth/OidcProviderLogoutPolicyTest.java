package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证发现元数据与配置变更边界，防止将持久化旧令牌发送到新身份服务。
 * @author owlzhangfq@gmail.com
 */
class OidcProviderLogoutPolicyTest {
    @Test
    void discoveryRejectsUnsafeLogoutEndpointsAndAbsenceDoesNotInventOne() {
        try (var provider = new OidcTestProvider()) {
            var config = new OidcClientConfiguration();
            var properties = properties(provider);
            assertThat(OidcProviderLogoutFilter.available(config.oidcClients(properties, new MockEnvironment(), false, "http://localhost"))).isFalse();
            provider.supportsLogout = true;
            for (Object value : List.of(12, "http://remote.invalid/logout", "javascript:alert(1)", "https://user:secret@example.org/logout", "https://example.org/logout#fragment")) {
                provider.logoutEndpointOverride = value;
                assertThatIllegalArgumentException().isThrownBy(() -> config.oidcClients(properties, new MockEnvironment(), false, "http://localhost"));
            }
            provider.logoutEndpointOverride = null;
            assertThat(OidcProviderLogoutFilter.available(config.oidcClients(properties, new MockEnvironment(), false, "http://localhost"))).isTrue();
        }
    }

    @Test
    void previouslyStoredIssuerOrClientCannotBeSentToCurrentProvider() throws Exception {
        try (var provider = new OidcTestProvider()) {
            provider.supportsLogout = true;
            var clients = new OidcClientConfiguration().oidcClients(properties(provider), new MockEnvironment(), false, "http://localhost");
            var json = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
            var filter = new OidcProviderLogoutFilter(clients, json, "http://localhost/");
            for (var claims : List.of(Map.of("sub", "person", "iss", "https://old.example", "aud", List.of("platform")),
                    Map.of("sub", "person", "iss", provider.issuer(), "aud", List.of("old-client")))) {
                var token = new OidcIdToken("must-not-leak", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), claims);
                var user = new PlatformOidcUser(token, "tenant-a", "person", Set.of("EMPLOYEE"));
                SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "enterprise"));
                var request = new MockHttpServletRequest("POST", OidcProviderLogoutFilter.PATH);
                request.addParameter("actor", json.write(List.of("tenant-a", "person")));
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> fail("Changed identity source must not reach logout"));
                assertThat(response.getStatus()).isEqualTo(409);
                assertThat(response.getContentAsString()).doesNotContain("must-not-leak");
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    private OidcProperties properties(OidcTestProvider provider) {
        return new OidcProperties(true, provider.issuer(), "platform", "fixture-secret", "tenant", "roles",
                Map.of("external", "tenant-a"), Map.of("staff", Set.of("EMPLOYEE")), true);
    }
}
