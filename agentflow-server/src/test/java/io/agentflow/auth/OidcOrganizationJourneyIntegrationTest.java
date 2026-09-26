package io.agentflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentflow.AgentflowApplication;
import io.agentflow.common.JsonUtil;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关闭演示认证，以真实签名授权码和 HTTP 会话串联本地组织与审批，避免注入身份掩盖集成缺口。
 * @author owlzhangfq@gmail.com
 */
class OidcOrganizationJourneyIntegrationTest {
    private static final OidcTestProvider PROVIDER = new OidcTestProvider();
    private static final String TENANT = "oidc-organization-a";
    private static final String REVIEWER = "issuer:reviewer/审批人";
    private static final String STAFF = "issuer:staff/员工";
    private static final String WEB_ORIGIN = "http://127.0.0.1:5198";
    private static ServletWebServerApplicationContext server;
    private static JsonUtil json;

    @BeforeAll
    static void start() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("server.port", "0");
        settings.put("server.address", "127.0.0.1");
        settings.put("spring.datasource.url", System.getProperty("agentflow.organization-oidc-test.jdbc-url",
                "jdbc:h2:mem:organization-oidc-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
        settings.put("spring.datasource.driver-class-name", System.getProperty("agentflow.organization-oidc-test.jdbc-driver", "org.h2.Driver"));
        settings.put("spring.datasource.username", System.getProperty("agentflow.organization-oidc-test.jdbc-user", "sa"));
        settings.put("spring.datasource.password", System.getProperty("agentflow.organization-oidc-test.jdbc-password", ""));
        settings.put("agentflow.auth.demo-enabled", false);
        settings.put("agentflow.auth.oidc.enabled", true);
        settings.put("agentflow.auth.session.jdbc-enabled", true);
        settings.put("agentflow.auth.oidc.issuer", PROVIDER.issuer());
        settings.put("agentflow.auth.oidc.client-id", "platform");
        settings.put("agentflow.auth.oidc.client-secret", "fixture-secret");
        settings.put("agentflow.auth.oidc.tenant-claim", "tenant");
        settings.put("agentflow.auth.oidc.roles-claim", "roles");
        settings.put("agentflow.auth.oidc.tenant-mappings.external", TENANT);
        settings.put("agentflow.auth.oidc.tenant-mappings.external-b", "oidc-organization-b");
        settings.put("agentflow.auth.oidc.role-mappings.staff[0]", "EMPLOYEE");
        settings.put("agentflow.auth.oidc.role-mappings.admins[0]", "ADMIN");
        settings.put("agentflow.auth.oidc.role-mappings.designers[0]", "PROCESS_ADMIN");
        settings.put("agentflow.auth.oidc.role-mappings.reviewers[0]", "APPROVER");
        settings.put("agentflow.auth.oidc.allow-insecure-loopback", true);
        settings.put("agentflow.web.allowed-origin", WEB_ORIGIN);
        settings.put("agentflow.sla.reminders-enabled", false);
        settings.put("logging.level.root", "WARN");
        server = (ServletWebServerApplicationContext) new SpringApplicationBuilder(AgentflowApplication.class).run(
                settings.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
        json = server.getBean(JsonUtil.class);
    }

    @AfterAll
    static void close() {
        if (server != null) server.close();
        PROVIDER.close();
    }

    @Test
    void oidcSessionsMaintainOrganizationPublishTemplateAndApproveWithoutLocalRoleEscalation() throws Exception {
        Browser administrator = login("organization-admin", "external", List.of("admins"));
        Browser designer = login("process-designer", "external", List.of("designers"));
        Browser employee = login(STAFF, "external", List.of("staff"));
        Browser reviewer = login(REVIEWER, "external", List.of("staff", "reviewers"));
        assertThat(read(administrator, "/organization", 200).path("initialized").asBoolean()).isFalse();
        read(designer, "/organization", 403);
        write(employee, "POST", "/organization/initialize", Map.of(), 403);
        // 使用真实会话防伪令牌，不能让目录初始化绕过统一入口。
        var missingCsrf = HttpRequest.newBuilder(uri("/organization/initialize"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString()).build();
        assertThat(administrator.client().send(missingCsrf, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(read(administrator, "/organization", 200).path("initialized").asBoolean()).isFalse();
        write(administrator, "POST", "/organization/initialize", Map.of(), 201);
        var legal = write(administrator, "POST", "/organization/units",
                Map.of("kind", "LEGAL_ENTITY", "name", "组织验收法人", "active", true), 201);
        var department = unit(administrator, legal, "DEPARTMENT", "审批部");
        var position = unit(administrator, legal, "POSITION", "审批岗");
        var reviewerPerson = person(administrator, REVIEWER, "审批人");
        var employeePerson = person(administrator, STAFF, "仅员工权限的候选人");
        for (var person : List.of(reviewerPerson, employeePerson)) {
            write(administrator, "POST", "/organization/appointments", Map.of("personId", person.path("id").asText(),
                    "departmentId", department.path("id").asText(), "positionId", position.path("id").asText(), "active", true), 201);
        }
        assertThat(read(employee, "/auth/me", 200).path("actor").path("roles")).containsExactly(json.read("\"EMPLOYEE\"", JsonNode.class));
        read(employee, "/process-definitions/assignee-options", 403);
        String rule = "role:ORG_UNIT_" + department.path("id").asText();
        assertThat(read(designer, "/process-definitions/assignee-options", 200).toString()).contains(rule);
        var draft = copyAndConfigureTemplate(designer, rule);
        var published = write(designer, "POST", "/process-definitions/" + draft.path("id").asText()
                + "/publish?expectedRevision=" + draft.path("revision").asLong(), Map.of("changeNote", "OIDC本地组织完整链路验收"), 200);
        assertThat(published.path("tenantId").asText()).isEqualTo(TENANT);
        var application = write(employee, "POST", "/applications", Map.of("businessNo", "oidc-org-" + UUID.randomUUID(),
                "title", "本地组织请假验收", "processKey", published.path("key").asText(), "definitionVersion", 1,
                "payload", Map.of("leaveType", "PERSONAL", "startDate", "2026-10-12", "durationDays", "1", "reason", "完成交接后请假。")), 201);
        String applicationPath = "/applications/" + application.path("id").asText();
        application = write(employee, "POST", applicationPath + "/submit", Map.of("expectedVersion", application.path("version").asLong()), 200);
        assertThat(application.path("status").asText()).isEqualTo("IN_APPROVAL");
        var pending = read(reviewer, "/tasks", 200);
        assertThat(pending).hasSize(1);
        var task = pending.get(0);
        assertThat(task.path("applicationId").asText()).isEqualTo(application.path("id").asText());
        String taskPath = "/tasks/" + task.path("taskId").asText();
        var action = Map.of("action", "APPROVE", "expectedVersion", task.path("version").asLong(), "comment", "组织权限验证通过");
        // 同为冻结候选人，本地资格不能替代 IdP 映射出的 APPROVER 权限。
        read(employee, taskPath, 403);
        write(employee, "POST", taskPath + "/actions", action, 403);
        Browser otherTenant = login(REVIEWER, "external-b", List.of("staff", "reviewers"));
        read(otherTenant, applicationPath, 404);
        var before = read(employee, applicationPath + "/rounds/1/diagram", 200);
        assertThat(before.toString()).contains(REVIEWER, STAFF, "candidateSnapshots");
        write(administrator, "PUT", "/organization/people/" + reviewerPerson.path("id").asText(),
                Map.of("displayName", "审批人", "active", false, "approvalEligible", true, "expectedRevision", 1), 200);
        read(reviewer, taskPath, 403);
        write(reviewer, "POST", taskPath + "/actions", action, 403);
        assertThat(read(employee, applicationPath, 200)).isEqualTo(application);
        assertThat(read(employee, applicationPath + "/rounds/1/diagram", 200).path("nodes")).isEqualTo(before.path("nodes"));
        write(administrator, "PUT", "/organization/people/" + reviewerPerson.path("id").asText(),
                Map.of("displayName", "审批人", "active", true, "approvalEligible", true, "expectedRevision", 2), 200);
        assertThat(write(reviewer, "POST", taskPath + "/actions", action, 200).path("applicationStatus").asText()).isEqualTo("APPROVED");
        var finalApplication = read(employee, applicationPath, 200);
        assertThat(finalApplication.path("status").asText()).isEqualTo("APPROVED");
        assertThat(finalApplication.path("definitionVersion").asInt()).isEqualTo(1);
        assertThat(finalApplication.path("roundNo").asInt()).isEqualTo(1);
        assertThat(read(reviewer, "/tasks", 200)).isEmpty();
    }

    private JsonNode copyAndConfigureTemplate(Browser designer, String rule) throws Exception {
        var templates = read(designer, "/process-templates", 200);
        JsonNode template = null;
        for (var candidate : templates) if (candidate.path("key").asText().equals("leave-request")) template = candidate;
        assertThat(template).isNotNull();
        var draft = write(designer, "POST", "/process-templates/leave-request/copy", Map.of("key", "oidc-org-" + UUID.randomUUID(),
                "name", "组织请假", "templateVersion", template.path("templateVersion").asLong()), 200);
        ObjectNode graph = draft.path("graph").deepCopy();
        for (var node : graph.path("nodes")) {
            if (node.path("type").asText().equals("USER_TASK")) ((ObjectNode) node.path("properties")).put("assigneeRule", rule);
        }
        return write(designer, "PUT", "/process-definitions/" + draft.path("id").asText(), Map.of("name", "组织请假", "graph", graph,
                "formSchema", draft.path("formSchema"), "notificationTexts", draft.path("notificationTexts"),
                "expectedRevision", draft.path("revision").asLong()), 200);
    }

    private JsonNode unit(Browser administrator, JsonNode legal, String kind, String name) throws Exception {
        return write(administrator, "POST", "/organization/units", Map.of("kind", kind, "name", name,
                "legalEntityId", legal.path("id").asText(), "active", true), 201);
    }

    private JsonNode person(Browser administrator, String subject, String name) throws Exception {
        return write(administrator, "POST", "/organization/people", Map.of("subject", subject, "displayName", name,
                "active", true, "approvalEligible", true), 201);
    }

    private Browser login(String subject, String tenant, List<String> roles) throws Exception {
        PROVIDER.subject = subject;
        PROVIDER.tenant = tenant;
        PROVIDER.roles = roles;
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        var authorization = client.send(HttpRequest.newBuilder(uri("/auth/oidc/authorize/enterprise")).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(authorization.statusCode()).isEqualTo(302);
        var grant = client.send(HttpRequest.newBuilder(URI.create(authorization.headers().firstValue("Location").orElseThrow())).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(grant.statusCode()).isEqualTo(302);
        var callback = URI.create(grant.headers().firstValue("Location").orElseThrow());
        // 对外回调为前端代理地址，测试直接转发同一原始路径和查询到随机端口服务。
        var completed = client.send(HttpRequest.newBuilder(uri(callback.getRawPath().substring("/api/v1".length()) + "?" + callback.getRawQuery())).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(completed.statusCode()).isEqualTo(302);
        assertThat(completed.headers().firstValue("Location")).contains(WEB_ORIGIN + "/");
        var browser = new Browser(client, URLEncoder.encode(json.write(List.of(tenant.equals("external") ? TENANT : "oidc-organization-b", subject)), StandardCharsets.UTF_8));
        var identity = read(browser, "/auth/me", 200).path("actor");
        assertThat(identity.path("userId").asText()).isEqualTo(subject);
        return browser;
    }

    private JsonNode read(Browser browser, String path, int expected) throws Exception {
        var response = browser.client().send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header("X-AgentFlow-Actor", browser.actor()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(expected);
        return json.read(response.body(), JsonNode.class);
    }

    private JsonNode write(Browser browser, String method, String path, Object body, int expected) throws Exception {
        var options = read(browser, "/auth/options", 200);
        assertThat(options.path("mode").asText()).isEqualTo("OIDC");
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .header(options.path("csrfHeader").asText(), options.path("csrfToken").asText())
                .header("X-AgentFlow-Actor", browser.actor()).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.write(body))).build();
        var response = browser.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
        return json.read(response.body(), JsonNode.class);
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getWebServer().getPort() + "/api/v1" + path);
    }

    /**
     * 独立 Cookie 会话与页面绑定身份，不保存或记录原始令牌。
     * @author owlzhangfq@gmail.com
     */
    private record Browser(HttpClient client, String actor) { }
}
