package io.agentflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 验证迁移保护、签发方/客户端隔离和数据库顺序，避免身份源时钟影响注销范围。
 *
 * @author owlzhangfq@gmail.com
 */
class OidcLogoutScopesTest {
    @Test
    void migrationKeepsExistingSessionsAndScopesAreIsolatedByIssuerAndClient() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:logout-scopes-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("21").load().migrate();
        jdbc.update("""
                INSERT INTO AF_HTTP_SESSION VALUES (?, ?, 1, 2, 1800, 1800002, 'person')
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var before = jdbc.queryForList("SELECT * FROM AF_HTTP_SESSION");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM AF_HTTP_SESSION")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM AF_OIDC_LOGOUT_SCOPE")).isEmpty();
        var manager = new DataSourceTransactionManager(source);
        var json = new JsonUtil(new ObjectMapper());
        var original = new OidcLogoutScopes(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.auth.mapper.OidcLogoutScopesMapper.class), json, properties("https://id.example", "flow"), manager);
        var otherClient = new OidcLogoutScopes(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.auth.mapper.OidcLogoutScopesMapper.class), json, properties("https://id.example", "other"), manager);
        var otherIssuer = new OidcLogoutScopes(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.auth.mapper.OidcLogoutScopesMapper.class), json, properties("https://other.example", "flow"), manager);
        Instant time = Instant.parse("2026-09-24T00:00:00Z");
        var logout = Jwt.withTokenValue("verified-in-test").header("alg", "RS256")
                .subject("person").jti("unique-event").issuedAt(time).expiresAt(time.plusSeconds(120)).build();
        long loginOrder = original.beginLogin();
        original.revoke(logout);
        var sameSecond = new OidcIdToken("fixture", time, time.plusSeconds(300), Map.of("sub", "person"));
        assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> original.requireActive(sameSecond, loginOrder));
        assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> original.requireActive(sameSecond, null));
        otherClient.requireActive(sameSecond, loginOrder);
        otherIssuer.requireActive(sameSecond, loginOrder);
        long freshOrder = original.beginLogin();
        original.requireActive(sameSecond, freshOrder);
        original.revoke(logout);
        assertThat(original.beginLogin()).isEqualTo(freshOrder);
        original.requireActive(sameSecond, freshOrder);
        assertThat(jdbc.queryForList("SELECT SCOPE_HASH FROM AF_OIDC_LOGOUT_SCOPE", String.class))
                .hasSize(1).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void enablingBackchannelRequiresEnterpriseAuthenticationAndSharedSessions() {
        var configuration = new OidcBackchannelConfiguration();
        for (boolean[] mode : new boolean[][] {{false, false, true}, {true, true, true}, {true, false, false}}) {
            var oidc = new OidcProperties(mode[0], null, null, null, null, null, Map.of(), Map.of(), false);
            assertThatIllegalArgumentException().isThrownBy(() ->
                    configuration.oidcLogoutScopes(null, null, oidc, null, mode[1], mode[2]));
        }
    }

    private static OidcProperties properties(String issuer, String client) {
        return new OidcProperties(true, issuer, client, "unused", "tenant", "roles", Map.of(), Map.of(), false);
    }
}
