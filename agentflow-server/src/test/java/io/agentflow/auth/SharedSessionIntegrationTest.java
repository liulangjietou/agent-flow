package io.agentflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.AgentflowApplication;
import io.agentflow.database.DatabaseSchemaLifecycle;
import io.agentflow.definition.DefinitionModels.Graph;
import io.agentflow.definition.DefinitionModels.Node;
import io.agentflow.definition.DefinitionModels.NodeType;
import io.agentflow.definition.DefinitionModels.Edge;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.DefinitionDeploymentPort;
import io.agentflow.integration.JdbcWebhookStore;
import io.agentflow.integration.WebhookTargets;
import io.agentflow.integration.DeliveryProgress;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import java.time.Instant;
import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.Executors;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个独立 HTTP 服务共享专用数据库，验证真实授权码跨实例回调、退出及重启恢复。
 * @author owlzhangfq@gmail.com
 */
class SharedSessionIntegrationTest {
    private static final OidcTestProvider PROVIDER = new OidcTestProvider();
    private static final String JDBC_URL = setting("url", "URL",
            "jdbc:h2:mem:shared-session-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private static final String AUTHORIZATION_PATH = "/api/v1/auth/oidc/authorize/enterprise";
    private static final String CURRENT_USER_PATH = "/api/v1/auth/me";
    private static final HttpClient DIRECT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ServletWebServerApplicationContext first;
    private static ServletWebServerApplicationContext second;
    private static JsonUtil json;
    private static final String PROCESS_KEY = "shared-runtime-fixture";

    @BeforeAll
    static void start() {
        // 按部署流程初始化连接的当前 schema，避免其他 schema 的引擎表干扰首次启动探测。
        DatabaseSchemaLifecycle.migrate(new DriverManagerDataSource(JDBC_URL,
                setting("user", "USERNAME", "sa"), setting("password", "PASSWORD", "")));
        first = node(Map.of());
        second = node(Map.of());
        json = first.getBean(JsonUtil.class);
        seedPublishedDefinition();
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
    void concurrentWritesAcrossNodesCommitOneApplicationRoundAndApproval() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        String csrf = json.read(browser.get(first, "/api/v1/auth/options").body(), JsonNode.class).path("csrfToken").asText();
        String cookie = browser.cookie();
        String businessNo = "CLUSTER-" + UUID.randomUUID();
        String body = json.write(Map.of("businessNo", businessNo, "processKey", PROCESS_KEY, "definitionVersion", 1,
                "title", "跨实例审批", "payload", Map.of()));
        var created = sameWriteOnBothNodes(cookie, csrf, "/api/v1/applications", body);
        String id = json.read(created.body(), JsonNode.class).path("id").asText();
        sameWriteOnBothNodes(cookie, csrf, "/api/v1/applications/" + id + "/submit", "{\"expectedVersion\":1}");
        var jdbc = second.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_application WHERE business_no=?", Integer.class, businessNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_submission_round WHERE application_id=?", Integer.class, id)).isEqualTo(1);
        var task = second.getBean(TaskService.class).createTaskQuery()
                .processVariableValueEquals("applicationId", id).singleResult();
        sameWriteOnBothNodes(cookie, csrf, "/api/v1/tasks/" + task.getId() + "/actions",
                "{\"expectedVersion\":2,\"action\":\"APPROVE\",\"comment\":\"跨实例只执行一次\"}");
        assertThat(jdbc.queryForObject("SELECT status FROM approval_application WHERE id=?", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE application_id=?", String.class, id))
                .containsExactlyInAnyOrder("CREATE", "SUBMIT", "APPROVE");
        assertThat(second.getBean(RuntimeService.class).createProcessInstanceQuery()
                .variableValueEquals("applicationId", id).count()).isZero();
    }

    @Test
    void anotherNodeReplaysCommittedResponseAndFinishesPendingTaskAfterNodeStops() throws Exception {
        Browser browser = browser();
        login(browser, first, second);
        String csrf = json.read(browser.get(first, "/api/v1/auth/options").body(), JsonNode.class).path("csrfToken").asText();
        String cookie = browser.cookie();
        String createKey = UUID.randomUUID().toString();
        String body = json.write(Map.of("businessNo", "FAILOVER-" + UUID.randomUUID(), "processKey", PROCESS_KEY,
                "definitionVersion", 1, "title", "节点退出后继续办理", "payload", Map.of()));
        var created = mutation(first, cookie, csrf, createKey, "/api/v1/applications", body);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.read(created.body(), JsonNode.class).path("id").asText();
        assertThat(mutation(first, cookie, csrf, UUID.randomUUID().toString(), "/api/v1/applications/" + id + "/submit",
                "{\"expectedVersion\":1}").statusCode()).isEqualTo(200);
        first.close();
        try {
            var replay = mutation(second, cookie, csrf, createKey, "/api/v1/applications", body);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(created.body());
            assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
            var task = second.getBean(TaskService.class).createTaskQuery()
                    .processVariableValueEquals("applicationId", id).singleResult();
            var approved = mutation(second, cookie, csrf, UUID.randomUUID().toString(), "/api/v1/tasks/" + task.getId() + "/actions",
                    "{\"expectedVersion\":2,\"action\":\"APPROVE\"}");
            assertThat(approved.statusCode()).isEqualTo(200);
            assertThat(json.read(approved.body(), JsonNode.class).path("applicationStatus").asText()).isEqualTo("APPROVED");
        } finally { first = node(Map.of()); }
    }

    @Test
    void deliveryLeaseHasOneOwnerAcrossNodesAndRejectsLateConfirmation() throws Exception {
        var left = first.getBean(JdbcWebhookStore.class);
        var right = second.getBean(JdbcWebhookStore.class);
        var now = Instant.now();
        String event = UUID.randomUUID().toString();
        var target = new WebhookTargets.Destination("fixture", "tenant-a", "验收目的地",
                URI.create("https://fixture.invalid/webhook"), new byte[32], true, "fixture-digest");
        new TransactionTemplate(first.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                left.append("tenant-a", target, event, "ApplicationSubmitted", UUID.randomUUID(), 2, "{}", now));
        var jdbc = second.getBean(JdbcTemplate.class);
        UUID id = UUID.fromString(jdbc.queryForObject("SELECT id FROM webhook_delivery WHERE event_id=?", String.class, event));
        var claimed = race(() -> left.claim(id, now.plusSeconds(1)), () -> right.claim(id, now.plusSeconds(1)));
        assertThat(claimed.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        var winner = claimed.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        var replacement = right.claim(id, winner.progress().leaseUntil().plusSeconds(1));
        var success = DeliveryProgress.Outcome.http(204);
        assertThat(left.finish(winner, success, now.plusSeconds(2))).isFalse();
        assertThat(right.finish(replacement, success, replacement.updatedAt().plusSeconds(1))).isTrue();
        assertThat(jdbc.queryForList("SELECT result FROM webhook_attempt WHERE delivery_id=? ORDER BY attempt_no", String.class, id.toString()))
                .containsExactly("OUTCOME_UNKNOWN", "DELIVERED");
    }

    private HttpResponse<String> sameWriteOnBothNodes(String cookie, String csrf, String path, String body) throws Exception {
        String key = UUID.randomUUID().toString();
        var responses = race(() -> mutation(first, cookie, csrf, key, path, body),
                () -> mutation(second, cookie, csrf, key, path, body));
        assertThat(responses.get(0).statusCode()).isIn(200, 201);
        assertThat(responses.get(1).statusCode()).isEqualTo(responses.get(0).statusCode());
        assertThat(responses.get(1).body()).isEqualTo(responses.get(0).body());
        assertThat(responses.stream().map(r -> r.headers().firstValue("Idempotency-Replayed").orElseThrow()))
                .containsExactlyInAnyOrder("true", "false");
        return responses.get(0);
    }

    private static HttpResponse<String> mutation(ServletWebServerApplicationContext node, String cookie, String csrf,
                                                  String key, String path, String body) {
        try {
            return DIRECT.send(HttpRequest.newBuilder(uri(node, path)).timeout(Duration.ofSeconds(15))
                    .header("Cookie", cookie).header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", key)
                    .header("X-AgentFlow-Actor", "%5B%22tenant-a%22%2C%22employee-42%22%5D")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) { throw new AssertionError("Business request failed", failure); }
    }

    private static <T> List<T> race(Supplier<T> left, Supplier<T> right) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var jobs = List.of(left, right).stream().map(operation -> CompletableFuture.supplyAsync(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Concurrent request barrier failed", failure); }
                return operation.get();
            }, executor)).toList();
            // claim 竞争的败方合法返回 null，不能使用拒绝 null 的 List.of 收集结果。
            return Arrays.asList(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    private static void seedPublishedDefinition() {
        // 生产发布仍受组织目录约束；这里只准备已发布版本的测试前置数据。
        var graph = new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "user:employee-42")),
                new Node("end", "结束", NodeType.END, Map.of())),
                List.of(new Edge("a", "start", "review", ""),
                        new Edge("b", "review", "end", "")));
        new TransactionTemplate(first.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            var repository = first.getBean(DefinitionDraftRepository.class);
            if (repository.findPublished("tenant-a", PROCESS_KEY, 1).isPresent()) return;
            var definition = DefinitionDraft.create(UUID.randomUUID(), "tenant-a", PROCESS_KEY, "已有发布流程夹具", graph);
            repository.save(definition);
            definition.publish(0, 1);
            repository.save(definition);
            first.getBean(DefinitionDeploymentPort.class).deploy(definition);
        });
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
        properties.put("spring.datasource.driver-class-name", setting("driver", "DRIVER", "org.h2.Driver"));
        properties.put("spring.datasource.username", setting("user", "USERNAME", "sa"));
        properties.put("spring.datasource.password", setting("password", "PASSWORD", ""));
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
        properties.put("agentflow.auth.oidc.role-mappings.staff[1]", "APPROVER");
        properties.put("agentflow.webhooks.worker-enabled", false);
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

    private static String setting(String property, String environment, String fallback) {
        return System.getProperty("agentflow.session-test.jdbc-" + property,
                System.getenv().getOrDefault("AGENTFLOW_SESSION_TEST_" + environment, fallback));
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
