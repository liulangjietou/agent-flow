package io.agentflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.AgentflowApplication;
import io.agentflow.common.JsonUtil;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个独立 HTTP 服务共享专用数据库，验证真实授权码跨实例回调、退出及重启恢复。
 * @author owlzhangfq@gmail.com
 */
class SharedSessionIntegrationTest {
    private static final OidcTestProvider PROVIDER = new OidcTestProvider();
    private static final String JDBC_URL = System.getProperty("agentflow.session-test.jdbc-url",
            "jdbc:h2:mem:shared-session-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private static final String AUTHORIZATION_PATH = "/api/v1/auth/oidc/authorize/enterprise";
    private static final String CURRENT_USER_PATH = "/api/v1/auth/me";
    private static final HttpClient DIRECT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ServletWebServerApplicationContext first;
    private static ServletWebServerApplicationContext second;
    private static JsonUtil json;

    @BeforeAll
    static void start() {
        first = node(Map.of());
        second = node(Map.of());
        json = first.getBean(JsonUtil.class);
    }

    @BeforeEach
    void resetProvider() { PROVIDER.mode = "valid"; PROVIDER.subject = "employee-42"; PROVIDER.tenant = "external"; }

    @AfterAll
    static void close() {
        if (second != null) second.close();
        if (first != null) first.close();
        PROVIDER.close();
    }

    @Test
    void authorizationAndCallbackCanUseDifferentNodesAndRotateTheSharedSession() throws Exception {
        Browser browser = browser();
        assertThat(browser.get(first, "/api/v1/auth/options").statusCode()).isEqualTo(200);
        String previousCookie = browser.cookie();
        login(browser, first, second);
        assertThat(browser.cookie()).isNotEqualTo(previousCookie);
        for (var node : new ServletWebServerApplicationContext[] {first, second}) {
            var response = browser.get(node, CURRENT_USER_PATH);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.read(response.body(), JsonNode.class).path("actor").path("userId").asText()).isEqualTo("employee-42");
            assertThat(rawGet(node, CURRENT_USER_PATH, previousCookie).statusCode()).isEqualTo(401);
        }
        var attributes = first.getBean(JdbcTemplate.class).queryForList(
                "SELECT ATTRIBUTE_NAME FROM AF_HTTP_SESSION_ATTRIBUTES", String.class);
        assertThat(attributes).anyMatch(name -> name.equals("SPRING_SECURITY_CONTEXT"));
        assertThat(attributes).noneMatch(name -> name.contains("AUTHORIZED_CLIENT"));
    }

    @Test
    void logoutOnEitherNodeInvalidatesTheSameSessionEverywhereAndRequiresCsrf() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        String oldCookie = browser.cookie();
        var rejected = browser.post(second, "/api/v1/auth/logout", Map.of());
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(browser.get(first, CURRENT_USER_PATH).statusCode()).isEqualTo(200);
        JsonNode options = json.read(browser.get(first, "/api/v1/auth/options").body(), JsonNode.class);
        assertThat(browser.post(second, "/api/v1/auth/logout", Map.of("X-CSRF-TOKEN", options.path("csrfToken").asText()))
                .statusCode()).isEqualTo(204);
        assertThat(rawGet(first, CURRENT_USER_PATH, oldCookie).statusCode()).isEqualTo(401);
        assertThat(rawGet(second, CURRENT_USER_PATH, oldCookie).statusCode()).isEqualTo(401);
    }

    @Test
    void existingLoginSurvivesAFullBackendRestart() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        second.close();
        second = node(Map.of());
        assertThat(browser.get(second, CURRENT_USER_PATH).statusCode()).isEqualTo(200);
        assertThat(browser.get(first, CURRENT_USER_PATH).statusCode()).isEqualTo(200);
    }

    @Test
    void changedIdentityMappingRejectsPersistedPrivilegesAndInvalidatesOtherNodes() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        second.close();
        try {
            second = node(Map.of("agentflow.auth.oidc.role-mappings.staff[0]", "ADMIN"));
            assertThat(browser.get(second, CURRENT_USER_PATH).statusCode()).isEqualTo(401);
            assertThat(browser.get(first, CURRENT_USER_PATH).statusCode()).isEqualTo(401);
        } finally {
            second.close();
            second = node(Map.of());
        }
    }

    @Test
    void expiredDatabaseSessionCannotBeRecoveredByAnotherNode() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        first.getBean(JdbcTemplate.class).update("UPDATE AF_HTTP_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0");
        assertThat(browser.get(second, CURRENT_USER_PATH).statusCode()).isEqualTo(401);
        assertThat(browser.get(first, CURRENT_USER_PATH).statusCode()).isEqualTo(401);
    }

    @Test
    void legacyContainerAndMalformedCookiesDoNotBreakTheLoginEntry() throws Exception {
        // 旧容器使用原始标识；把它误当 Base64 解码可能产生 PostgreSQL 不接受的 NUL。
        for (String value : new String[] {"A".repeat(32), "AA==", "not-a-session"}) {
            var response = rawGet(first, "/api/v1/auth/options", "AGENTFLOW_SESSION=" + value);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.read(response.body(), JsonNode.class).path("mode").asText()).isEqualTo("OIDC");
        }
    }

    @Test
    void sharedSessionKeepsTenantAndActorBindingOnBusinessRequests() throws Exception {
        Browser owner = browser();
        login(owner, first, second);
        UUID id = UUID.randomUUID();
        first.getBean(io.agentflow.approval.repository.ApplicationRepository.class).save(
                io.agentflow.approval.model.Application.draft(id, "tenant-a", "SESSION-" + id,
                        "fixture", 1, "employee-42", "共享会话权限验收", Map.of()));
        assertThat(owner.get(second, "/api/v1/applications/" + id).statusCode()).isEqualTo(200);
        PROVIDER.tenant = "external-b";
        Browser otherTenant = browser();
        login(otherTenant, first, second);
        assertThat(otherTenant.get(first, "/api/v1/applications/" + id).statusCode()).isEqualTo(404);
        var request = HttpRequest.newBuilder(uri(second, "/api/v1/applications/" + id))
                .header("X-AgentFlow-Actor", "%5B%22tenant-a%22%2C%22wrong%22%5D").GET().build();
        assertThat(owner.client().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private static ServletWebServerApplicationContext node(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("server.address", "127.0.0.1");
        properties.put("spring.datasource.url", JDBC_URL);
        properties.put("spring.datasource.driver-class-name", System.getProperty("agentflow.session-test.jdbc-driver", "org.h2.Driver"));
        properties.put("spring.datasource.username", System.getProperty("agentflow.session-test.jdbc-user", "sa"));
        properties.put("spring.datasource.password", System.getProperty("agentflow.session-test.jdbc-password", ""));
        properties.put("agentflow.auth.demo-enabled", false);
        properties.put("agentflow.auth.oidc.enabled", true);
        properties.put("agentflow.auth.session.jdbc-enabled", true);
        properties.put("agentflow.auth.oidc.issuer", PROVIDER.issuer());
        properties.put("agentflow.auth.oidc.client-id", "platform");
        properties.put("agentflow.auth.oidc.client-secret", "fixture-secret");
        properties.put("agentflow.auth.oidc.tenant-claim", "tenant");
        properties.put("agentflow.auth.oidc.roles-claim", "roles");
        properties.put("agentflow.auth.oidc.tenant-mappings.external", "tenant-a");
        properties.put("agentflow.auth.oidc.tenant-mappings.external-b", "tenant-b");
        properties.put("agentflow.auth.oidc.role-mappings.staff[0]", "EMPLOYEE");
        properties.put("agentflow.auth.oidc.allow-insecure-loopback", true);
        properties.put("agentflow.web.allowed-origin", "http://127.0.0.1:5197");
        properties.put("logging.level.root", "WARN");
        properties.putAll(overrides);
        String[] arguments = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(AgentflowApplication.class).run(arguments);
    }

    private void login(Browser browser, ServletWebServerApplicationContext start, ServletWebServerApplicationContext callback) throws Exception {
        var authorization = browser.get(start, AUTHORIZATION_PATH);
        assertThat(authorization.statusCode()).isEqualTo(302);
        var grant = DIRECT.send(HttpRequest.newBuilder(URI.create(authorization.headers().firstValue("Location").orElseThrow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(grant.statusCode()).isEqualTo(302);
        URI location = URI.create(grant.headers().firstValue("Location").orElseThrow());
        var result = browser.get(callback, location.getRawPath() + "?" + location.getRawQuery());
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location").orElseThrow()).isEqualTo("http://127.0.0.1:5197/");
    }

    private static URI uri(ServletWebServerApplicationContext node, String path) {
        return URI.create("http://127.0.0.1:" + node.getWebServer().getPort() + path);
    }

    private static HttpResponse<String> rawGet(ServletWebServerApplicationContext node, String path, String cookie) throws Exception {
        return DIRECT.send(HttpRequest.newBuilder(uri(node, path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Browser browser() {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new Browser(cookies, HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build());
    }

    /**
     * 独立浏览器 Cookie 容器；身份令牌和会话标识不输出到测试日志。
     * @author owlzhangfq@gmail.com
     */
    private record Browser(CookieManager cookies, HttpClient client) {
        HttpResponse<String> get(ServletWebServerApplicationContext node, String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(node, path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(ServletWebServerApplicationContext node, String path, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(uri(node, path)).timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody());
            headers.forEach(request::header);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        String cookie() {
            var session = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("AGENTFLOW_SESSION")).findFirst().orElseThrow();
            return session.getName() + "=" + session.getValue();
        }
    }
}
