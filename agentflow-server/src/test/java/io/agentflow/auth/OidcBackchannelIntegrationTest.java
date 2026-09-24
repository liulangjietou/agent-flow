package io.agentflow.auth;

import io.agentflow.AgentflowApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 RSA 身份服务和两个独立 HTTP 实例，验证注销范围、持久化、重放及错误请求。
 * @author owlzhangfq@gmail.com
 */
class OidcBackchannelIntegrationTest {
    private static final OidcTestProvider PROVIDER = new OidcTestProvider();
    private static final HttpClient DIRECT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String JDBC_URL = System.getProperty("agentflow.backchannel-test.jdbc-url",
            "jdbc:h2:mem:backchannel-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private static final String CURRENT_USER = "/api/v1/auth/me";
    private static ServletWebServerApplicationContext first;
    private static ServletWebServerApplicationContext second;

    @BeforeAll
    static void start() { first = node(); second = node(); }

    @AfterAll
    static void close() {
        if (second != null) second.close();
        if (first != null) first.close();
        PROVIDER.close();
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.mode = "valid";
        PROVIDER.subject = "person-" + UUID.randomUUID();
        PROVIDER.tenant = "external";
        PROVIDER.sessionId = "session-" + UUID.randomUUID();
        PROVIDER.issuedSecondsAgo = 30;
    }

    @Test
    void sidTargetsOnlyItsProviderSessionAcrossNodes() throws Exception {
        HttpClient matching = login();
        String sid = PROVIDER.sessionId;
        PROVIDER.sessionId = "other-" + UUID.randomUUID();
        HttpClient sameUserOtherSession = login();
        assertSuccess(post(second, token(null, sid, Instant.now())));
        assertThat(get(matching, first, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(get(matching, second, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(get(sameUserOtherSession, first, CURRENT_USER).statusCode()).isEqualTo(200);
    }

    @Test
    void subjectLogsOutAllOfThatUsersSessionsButPreservesAnotherUser() throws Exception {
        String subject = PROVIDER.subject;
        HttpClient one = login();
        PROVIDER.sessionId = "second-" + UUID.randomUUID();
        HttpClient two = login();
        PROVIDER.subject = "other-" + UUID.randomUUID();
        HttpClient other = login();
        assertSuccess(post(second, token(subject, null, Instant.now())));
        assertThat(get(one, first, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(get(two, second, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(get(other, first, CURRENT_USER).statusCode()).isEqualTo(200);
    }

    @Test
    void bothIdentifiersMustMatchAndWatermarkSurvivesBackendRestart() throws Exception {
        HttpClient browser = login();
        assertSuccess(post(second, token("other-subject", PROVIDER.sessionId, Instant.now())));
        assertThat(get(browser, first, CURRENT_USER).statusCode()).isEqualTo(200);
        assertSuccess(post(second, token(PROVIDER.subject, PROVIDER.sessionId, Instant.now())));
        second.close();
        second = node();
        assertThat(get(browser, second, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(get(browser, first, CURRENT_USER).statusCode()).isEqualTo(401);
    }

    @Test
    void replayDoesNotLogOutFreshLoginAndOldCallbackCannotRecreateRevokedSession() throws Exception {
        HttpClient old = login();
        String notification = token(PROVIDER.subject, null, Instant.now().minusSeconds(10));
        assertSuccess(post(second, notification));
        assertThat(get(old, first, CURRENT_USER).statusCode()).isEqualTo(401);
        assertThat(callback(browser()).headers().firstValue("Location").orElseThrow()).endsWith("?authError=oidc");
        PROVIDER.issuedSecondsAgo = 0;
        HttpClient fresh = login();
        assertSuccess(post(first, notification));
        assertThat(get(fresh, second, CURRENT_USER).statusCode()).isEqualTo(200);
    }

    @Test
    void invalidSignaturesAndClaimsCannotChangeCurrentSessions() throws Exception {
        HttpClient browser = login();
        Map<String, Object> valid = claims(PROVIDER.subject, PROVIDER.sessionId, Instant.now());
        assertRejected(post(first, PROVIDER.logoutToken(valid, true, "logout+jwt")));
        assertRejected(post(first, PROVIDER.logoutToken(valid, false, "at+jwt")));
        for (String required : List.of("iss", "aud", "iat", "exp", "jti", "events")) {
            var candidate = new HashMap<>(valid); candidate.remove(required);
            assertRejected(post(first, PROVIDER.logoutToken(candidate, false, null)));
        }
        for (String field : List.of("nonce", "sub", "sid")) {
            var candidate = new HashMap<>(valid); candidate.put(field, null);
            assertRejected(post(first, PROVIDER.logoutToken(candidate, false, null)));
        }
        List<Map<String, Object>> invalid = List.of(
                Map.of("iss", "https://untrusted.invalid"), Map.of("aud", "another-client"),
                Map.of("aud", List.of("platform", 9)), Map.of("iat", Instant.now().plusSeconds(180).getEpochSecond()),
                Map.of("iat", Instant.now().minusSeconds(600).getEpochSecond()),
                Map.of("exp", Instant.now().minusSeconds(180).getEpochSecond()),
                Map.of("events", Map.of(OidcLogoutTokenValidator.EVENT, "wrong-type")),
                Map.of("events", List.of(OidcLogoutTokenValidator.EVENT)), Map.of("nonce", "id-token-nonce"),
                Map.of("iat", Long.toString(Instant.now().getEpochSecond())),
                Map.of("sub", 42), Map.of("sid", List.of("one")), Map.of("jti", ""), Map.of("sid", ""));
        for (var change : invalid) {
            var candidate = new HashMap<>(valid); candidate.putAll(change);
            assertRejected(post(first, PROVIDER.logoutToken(candidate, false, null)));
        }
        var noTarget = new HashMap<>(valid); noTarget.remove("sid"); noTarget.remove("sub");
        assertRejected(post(first, PROVIDER.logoutToken(noTarget, false, null)));
        assertThat(get(browser, second, CURRENT_USER).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentDuplicatesAndOutOfOrderNotificationsNeverMoveWatermarkBackwards() throws Exception {
        Instant older = Instant.now().minusSeconds(40);
        Instant newer = Instant.now().minusSeconds(5);
        String early = token(PROVIDER.subject, null, older);
        String late = token(PROVIDER.subject, null, newer);
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(DIRECT.sendAsync(postRequest(i % 2 == 0 ? first : second, i % 3 == 0 ? early : late),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (var call : calls) assertSuccess(call.get());
        assertThat(callback(browser()).headers().firstValue("Location").orElseThrow()).endsWith("?authError=oidc");
        assertSuccess(post(first, early));
        assertThat(callback(browser()).headers().firstValue("Location").orElseThrow()).endsWith("?authError=oidc");
    }

    @Test
    void endpointAcceptsOnlyOneBoundedFormTokenAndKeepsBusinessCsrfProtection() throws Exception {
        String signed = token(PROVIDER.subject, null, Instant.now());
        assertThat(get(DIRECT, first, OidcBackchannelLogoutFilter.PATH).statusCode()).isEqualTo(405);
        for (String body : List.of("", "logout_token=bad", "logout_token=" + signed + "&logout_token=" + signed,
                "logout_token=%ZZ", "logout_token=" + "a".repeat(32768))) {
            var request = HttpRequest.newBuilder(uri(first, OidcBackchannelLogoutFilter.PATH))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertRejected(DIRECT.send(request, HttpResponse.BodyHandlers.ofString()));
        }
        var jsonBody = HttpRequest.newBuilder(uri(first, OidcBackchannelLogoutFilter.PATH))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertRejected(DIRECT.send(jsonBody, HttpResponse.BodyHandlers.ofString()));
        var normalLogout = HttpRequest.newBuilder(uri(first, "/api/v1/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(DIRECT.send(normalLogout, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void databaseFailureNeverAcknowledgesLogoutOrAllowsAuthenticatedReads() throws Exception {
        HttpClient browser = login();
        var jdbc = first.getBean(JdbcTemplate.class);
        jdbc.execute("ALTER TABLE AF_OIDC_LOGOUT_SCOPE RENAME TO AF_OIDC_LOGOUT_SCOPE_UNAVAILABLE");
        try {
            assertThat(post(second, token(PROVIDER.subject, null, Instant.now())).statusCode()).isEqualTo(503);
            assertThat(get(browser, first, CURRENT_USER).statusCode()).isEqualTo(503);
        } finally {
            jdbc.execute("ALTER TABLE AF_OIDC_LOGOUT_SCOPE_UNAVAILABLE RENAME TO AF_OIDC_LOGOUT_SCOPE");
        }
        assertThat(get(browser, second, CURRENT_USER).statusCode()).isEqualTo(200);
    }

    private static ServletWebServerApplicationContext node() {
        Map<String, Object> settings = new HashMap<>(Map.of(
                "server.port", "0", "server.address", "127.0.0.1", "spring.datasource.url", JDBC_URL,
                "agentflow.auth.demo-enabled", false, "agentflow.auth.oidc.enabled", true,
                "agentflow.auth.session.jdbc-enabled", true, "agentflow.auth.backchannel.enabled", true,
                "agentflow.auth.oidc.issuer", PROVIDER.issuer(), "agentflow.auth.oidc.client-id", "platform",
                "agentflow.auth.oidc.client-secret", "fixture-secret"));
        settings.put("spring.datasource.driver-class-name", System.getProperty("agentflow.backchannel-test.jdbc-driver", "org.h2.Driver"));
        settings.put("spring.datasource.username", System.getProperty("agentflow.backchannel-test.jdbc-user", "sa"));
        settings.put("spring.datasource.password", System.getProperty("agentflow.backchannel-test.jdbc-password", ""));
        settings.put("agentflow.auth.oidc.tenant-claim", "tenant");
        settings.put("agentflow.auth.oidc.roles-claim", "roles");
        settings.put("agentflow.auth.oidc.tenant-mappings.external", "tenant-a");
        settings.put("agentflow.auth.oidc.role-mappings.staff[0]", "EMPLOYEE");
        settings.put("agentflow.auth.oidc.allow-insecure-loopback", true);
        settings.put("agentflow.web.allowed-origin", "http://127.0.0.1:5198");
        settings.put("logging.level.root", "WARN");
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(AgentflowApplication.class).run(
                settings.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpClient login() throws Exception {
        HttpClient browser = browser();
        var result = callback(browser);
        assertThat(result.headers().firstValue("Location").orElseThrow()).isEqualTo("http://127.0.0.1:5198/");
        assertThat(get(browser, first, CURRENT_USER).statusCode()).isEqualTo(200);
        return browser;
    }

    private HttpResponse<String> callback(HttpClient browser) throws Exception {
        var authorization = get(browser, first, "/api/v1/auth/oidc/authorize/enterprise");
        assertThat(authorization.statusCode()).isEqualTo(302);
        var grant = DIRECT.send(HttpRequest.newBuilder(URI.create(authorization.headers().firstValue("Location").orElseThrow()))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(grant.statusCode()).isEqualTo(302);
        URI callback = URI.create(grant.headers().firstValue("Location").orElseThrow());
        return get(browser, second, callback.getRawPath() + "?" + callback.getRawQuery());
    }

    private String token(String subject, String sid, Instant time) throws Exception {
        return PROVIDER.logoutToken(claims(subject, sid, time), false, "logout+jwt");
    }

    private Map<String, Object> claims(String subject, String sid, Instant time) {
        var claims = new HashMap<String, Object>(Map.of("iss", PROVIDER.issuer(), "aud", List.of("platform"),
                "iat", time.getEpochSecond(), "exp", time.plusSeconds(120).getEpochSecond(), "jti", UUID.randomUUID().toString(),
                "events", Map.of(OidcLogoutTokenValidator.EVENT, Map.of())));
        if (subject != null) claims.put("sub", subject);
        if (sid != null) claims.put("sid", sid);
        return claims;
    }

    private static HttpRequest postRequest(ServletWebServerApplicationContext node, String token) {
        return HttpRequest.newBuilder(uri(node, OidcBackchannelLogoutFilter.PATH)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("logout_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8))).build();
    }

    private static HttpResponse<String> post(ServletWebServerApplicationContext node, String token) throws Exception {
        return DIRECT.send(postRequest(node, token), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ServletWebServerApplicationContext node, String path) {
        return URI.create("http://127.0.0.1:" + node.getWebServer().getPort() + path);
    }

    private static HttpResponse<String> get(HttpClient browser, ServletWebServerApplicationContext node, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(node, path)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertSuccess(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static void assertRejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"error\":\"invalid_logout_token\"}");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
