package io.agentflow.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import static org.assertj.core.api.Assertions.*;

/**
 * 配置和声明的拒绝边界：不隐式创建身份，不把任意上游角色直接当作平台权限。
 * @author owlzhangfq@gmail.com
 */
class OidcIdentityPolicyTest {
    @Test
    void rejectsDemoMixingAndProductionLoopbackAndMissingMappings() {
        var properties = properties(false);
        assertThatCode(() -> properties.validate(false, true, "https://flow.example")).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> properties.validate(true, false, "https://flow.example"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true).validate(false, true, "https://flow.example"));
        var missing = new OidcProperties(true, "https://identity.example", "client", "secret", "tenant", "roles", Map.of(), Map.of(), false);
        assertThatIllegalArgumentException().isThrownBy(() -> missing.validate(false, false, "https://flow.example"));
        assertThat(properties.toString()).doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://identity.example", "https://user:password@identity.example", "https://identity.example/?callback=x", "https://identity.example/#fragment", "file:///etc/passwd", "https:///missing-host"})
    void rejectsUnsafeEndpointUrls(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(true).requireTrustedUrl(value));
    }

    @Test
    void onlyExplicitLoopbackAllowsPlainHttpAndOriginHasNoPath() {
        assertThatCode(() -> properties(true).requireTrustedUrl("http://127.0.0.1:9000/realm")).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> properties(false).requireTrustedUrl("http://localhost:9000"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(false).validate(false, false, "https://flow.example/other"));
    }

    @Test
    void unknownRolesNeverGrantPrivilegeAndWrongClaimTypesAreRejected() {
        var mapper = new OidcActorMapper(properties(false));
        Map<String, Object> claims = new HashMap<>(Map.of("sub", "stable-sub", "tenant", "external", "roles", List.of("staff", "ADMIN")));
        assertThat(mapper.map(token(claims), Instant.now()).roles()).containsExactly("EMPLOYEE");
        for (Object invalid : List.of("staff", List.of(1), List.of(), List.of("ADMIN"))) {
            claims.put("roles", invalid);
            assertThatThrownBy(() -> mapper.map(token(claims), Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
        }
        claims.put("roles", List.of("staff")); claims.put("tenant", List.of("external"));
        assertThatThrownBy(() -> mapper.map(token(claims), Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
        claims.put("tenant", "external"); claims.put("sub", " ");
        assertThatThrownBy(() -> mapper.map(token(claims), Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void httpsDeploymentForcesHttpOnlySecureSameSiteCookie() {
        var factory = new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory();
        new OidcClientConfiguration().oidcSessionCookie("https://flow.example").customize(factory);
        var cookie = factory.getSession().getCookie();
        assertThat(cookie.getName()).isEqualTo("AGENTFLOW_SESSION");
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(org.springframework.boot.web.server.Cookie.SameSite.LAX);
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    private OidcIdToken token(Map<String, Object> claims) {
        return new OidcIdToken("verified-fixture", Instant.now().minusSeconds(1), Instant.now().plusSeconds(60), claims);
    }

    private OidcProperties properties(boolean loopback) {
        return new OidcProperties(true, "https://identity.example", "client", "secret", "tenant", "roles",
                Map.of("external", "tenant-a"), Map.of("staff", Set.of("EMPLOYEE")), loopback);
    }
}
