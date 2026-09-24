package io.agentflow.auth;

import io.agentflow.AgentflowApplication;
import io.agentflow.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.util.HtmlUtils;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实授权码、跨实例会话与身份源 POST 验证主动退出，不以构造重定向代替协议验证。
 * @author owlzhangfq@gmail.com
 */
class OidcProviderLogoutIntegrationTest {
    private static final OidcTestProvider PROVIDER = new OidcTestProvider();
    private static final HttpClient DIRECT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonUtil JSON = new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper());
    private static final String HOME = "http://localhost:5199/";
    private static final String JDBC = System.getProperty("agentflow.provider-logout-test.jdbc-url",
            "jdbc:h2:mem:provider-logout-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private static ServletWebServerApplicationContext first;
    private static ServletWebServerApplicationContext second;

    @BeforeAll static void start() { PROVIDER.supportsLogout = true; first = node(); second = node(); }
    @AfterAll static void close() {
        if (second != null) second.close();
        if (first != null) first.close();
        PROVIDER.close();
    }
    @BeforeEach void reset() { PROVIDER.subject = "employee-" + UUID.randomUUID(); }

    @Test
    void postsVerifiedIdentityToProviderAndClearsSharedSessionBeforeLeaving() throws Exception {
        HttpClient browser = login();
        JsonNode options = options(browser);
        assertThat(options.path("providerLogoutUrl").asText()).isEqualTo(OidcProviderLogoutFilter.PATH);
        assertThat(options.path("csrfParameter").asText()).isEqualTo("_csrf");
        var response = logout(browser, OidcProviderLogoutFilter.PATH + "?returnUrl=https://attacker.invalid", form(options), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("nonce-");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.body()).contains("method=\"POST\"").doesNotContain("attacker.invalid");
        Map<String, String> fields = fields(response.body());
        assertThat(fields.get("post_logout_redirect_uri")).isEqualTo(HOME);
        assertThat(fields.get("id_token_hint")).isNotBlank();
        assertThat(get(browser, first, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThat(get(browser, second, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        var action = Pattern.compile("action=\"([^\"]+)\"").matcher(response.body());
        assertThat(action.find()).isTrue();
        assertThat(HtmlUtils.htmlUnescape(action.group(1))).isEqualTo(PROVIDER.issuer() + "/end-session");
        int previous = PROVIDER.logoutRequests;
        var providerResponse = DIRECT.send(HttpRequest.newBuilder(URI.create(PROVIDER.issuer() + "/end-session"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(encode(fields)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(providerResponse.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.logoutRequests).isEqualTo(previous + 1);
        assertThat(PROVIDER.lastLogoutSubject).isEqualTo(PROVIDER.subject);
    }

    @Test
    void localLogoutKeepsItsNoContentContractAndNeverCallsTheProvider() throws Exception {
        HttpClient browser = login();
        int before = PROVIDER.logoutRequests;
        var response = logout(browser, "/api/v1/auth/logout", form(options(browser)), false);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(PROVIDER.logoutRequests).isEqualTo(before);
        assertThat(get(browser, first, "/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void getAndMissingCsrfCannotTerminateTheCurrentSession() throws Exception {
        HttpClient browser = login();
        var get = get(browser, second, OidcProviderLogoutFilter.PATH);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).contains("POST");
        assertThat(logout(browser, OidcProviderLogoutFilter.PATH, "actor=forged", false).statusCode()).isEqualTo(403);
        assertThat(get(browser, first, "/api/v1/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void staleOrAmbiguousPageIdentityCannotLogOutAnotherAccount() throws Exception {
        HttpClient browser = login();
        String form = form(options(browser));
        for (String body : new String[] { form.replace(URLEncoder.encode(PROVIDER.subject, StandardCharsets.UTF_8), "other"), form + "&actor=duplicate" }) {
            var response = logout(browser, OidcProviderLogoutFilter.PATH, body, false);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).doesNotContain("id_token_hint");
        }
        assertThat(get(browser, first, "/api/v1/auth/me").statusCode()).isEqualTo(200);
    }

    @Test
    void anonymousCsrfSessionCannotCreateAProviderLogoutRequest() throws Exception {
        HttpClient browser = browser();
        var response = logout(browser, OidcProviderLogoutFilter.PATH, form(options(browser)), false);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("id_token_hint");
    }

    private String form(JsonNode options) {
        return encode(Map.of(options.path("csrfParameter").asText(), options.path("csrfToken").asText(),
                "actor", JSON.write(java.util.List.of("tenant-a", PROVIDER.subject))));
    }
    private JsonNode options(HttpClient browser) throws Exception {
        var result = get(browser, first, "/api/v1/auth/options");
        assertThat(result.statusCode()).isEqualTo(200);
        return JSON.read(result.body(), JsonNode.class);
    }
    private HttpResponse<String> logout(HttpClient browser, String path, String body, boolean forgedHost) throws Exception {
        var request = HttpRequest.newBuilder(uri(second, path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (forgedHost) request.header("X-Forwarded-Host", "attacker.invalid").header("X-Forwarded-Proto", "https");
        return browser.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Map<String, String> fields(String html) {
        var matcher = Pattern.compile("<input name=\"([^\"]+)\" type=\"hidden\" value=\"([^\"]*)\"").matcher(html);
        Map<String, String> fields = new LinkedHashMap<>();
        while (matcher.find()) fields.put(HtmlUtils.htmlUnescape(matcher.group(1)), HtmlUtils.htmlUnescape(matcher.group(2)));
        return fields;
    }
    private static String encode(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    private HttpClient login() throws Exception {
        HttpClient browser = browser();
        var authorization = get(browser, first, OidcClientConfiguration.AUTHORIZATION_BASE + "/enterprise");
        var grant = DIRECT.send(HttpRequest.newBuilder(URI.create(authorization.headers().firstValue("Location").orElseThrow())).GET().build(), HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(grant.headers().firstValue("Location").orElseThrow());
        var response = get(browser, second, callback.getRawPath() + "?" + callback.getRawQuery());
        assertThat(response.headers().firstValue("Location")).contains(HOME);
        return browser;
    }
    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private static URI uri(ServletWebServerApplicationContext node, String path) { return URI.create("http://127.0.0.1:" + node.getWebServer().getPort() + path); }
    private static HttpResponse<String> get(HttpClient browser, ServletWebServerApplicationContext node, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(node, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static ServletWebServerApplicationContext node() {
        Map<String, Object> settings = new HashMap<>(Map.of(
                "server.port", "0", "server.address", "127.0.0.1", "spring.datasource.url", JDBC,
                "agentflow.auth.demo-enabled", false, "agentflow.auth.oidc.enabled", true,
                "agentflow.auth.session.jdbc-enabled", true, "agentflow.auth.oidc.issuer", PROVIDER.issuer(),
                "agentflow.auth.oidc.client-id", "platform", "agentflow.auth.oidc.client-secret", "fixture-secret"));
        settings.put("spring.datasource.driver-class-name", System.getProperty("agentflow.provider-logout-test.jdbc-driver", "org.h2.Driver"));
        settings.put("spring.datasource.username", System.getProperty("agentflow.provider-logout-test.jdbc-user", "sa"));
        settings.put("spring.datasource.password", System.getProperty("agentflow.provider-logout-test.jdbc-password", ""));
        settings.put("agentflow.auth.oidc.tenant-claim", "tenant");
        settings.put("agentflow.auth.oidc.roles-claim", "roles");
        settings.put("agentflow.auth.oidc.tenant-mappings.external", "tenant-a");
        settings.put("agentflow.auth.oidc.role-mappings.staff[0]", "EMPLOYEE");
        settings.put("agentflow.auth.oidc.allow-insecure-loopback", true);
        settings.put("agentflow.web.allowed-origin", HOME.substring(0, HOME.length() - 1));
        settings.put("logging.level.root", "WARN");
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(AgentflowApplication.class).run(
                settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
}
