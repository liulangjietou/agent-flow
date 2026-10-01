package io.agentflow.system;

import com.sun.net.httpserver.HttpServer;
import io.agentflow.auth.AuthService;
import io.agentflow.agent.AssistConfiguration;
import io.agentflow.common.JsonUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实配置下的自检只报告当前租户接入事实，不外发内容或把配置当作连接验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:system-adapter-checks;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true",
        "agentflow.assist.enabled=true", "agentflow.assist.worker-enabled=true",
        "agentflow.assist.model=private-fixture-model", "agentflow.assist.api-key=private-model-secret",
        "agentflow.notifications.delivery-worker-enabled=true",
        "agentflow.notifications.allow-insecure-in-demo=true",
        "agentflow.notifications.public-url=https://private-approval.example.invalid/",
        "agentflow.notifications.wecom-apps.local.tenant-id=demo",
        "agentflow.notifications.wecom-apps.local.corp-id=private-corp",
        "agentflow.notifications.wecom-apps.local.agent-id=100001",
        "agentflow.notifications.wecom-apps.local.secret=private-wecom-secret",
        "agentflow.notifications.wecom-apps.local.enabled=true",
        "agentflow.notifications.bindings.local.tenant-id=demo",
        "agentflow.notifications.bindings.local.recipient=alice",
        "agentflow.notifications.bindings.local.channel=ENTERPRISE_IM",
        "agentflow.notifications.bindings.local.server-id=local",
        "agentflow.notifications.bindings.local.address=PrivateAlice",
        "agentflow.notifications.bindings.local.enabled=true",
        "agentflow.notifications.smtp-servers.other.tenant-id=other",
        "agentflow.notifications.smtp-servers.other.host=private-mail.example.invalid",
        "agentflow.notifications.smtp-servers.other.port=587",
        "agentflow.notifications.smtp-servers.other.security=STARTTLS",
        "agentflow.notifications.smtp-servers.other.from=sender@example.invalid",
        "agentflow.notifications.smtp-servers.other.enabled=true",
        "agentflow.notifications.bindings.other.tenant-id=other",
        "agentflow.notifications.bindings.other.recipient=alice",
        "agentflow.notifications.bindings.other.channel=EMAIL",
        "agentflow.notifications.bindings.other.server-id=other",
        "agentflow.notifications.bindings.other.address=private-alice@example.invalid",
        "agentflow.notifications.bindings.other.enabled=true"
})
@AutoConfigureMockMvc
class SystemAdapterChecksIntegrationTest {
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer PROVIDER = provider();
    private static final List<String> TABLES = List.of("notification_inbox", "notification_dispatch", "agent_assist_run", "audit_event");
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonUtil json;
    @Autowired AssistConfiguration assist;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        registry.add("agentflow.assist.endpoint", () -> base + "/v1/chat/completions");
        registry.add("agentflow.notifications.wecom-apps.local.base-url", () -> base);
    }

    @AfterAll
    static void stopProvider() { PROVIDER.stop(0); }

    @Test
    void configuredAdaptersAreTenantScopedReadOnlyAndDoNotSend() throws Exception {
        List<Integer> before = counts();
        String content = mvc.perform(get("/api/v1/system/checks")
                        .header("Authorization", "Bearer " + auth.login("demo", "admin", "demo").token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var report = json.read(content, SystemCheckService.Report.class);
        assertThat(report.checks()).filteredOn(check -> check.id().equals("notifications")).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo(SystemCheckService.Status.WARNING);
            assertThat(check.code()).isEqualTo("NOTIFICATION_CHANNELS_CONFIGURED");
            assertThat(check.message()).contains("企业 IM").doesNotContain("邮件");
        });
        assertThat(report.checks()).filteredOn(check -> check.id().equals("model")).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo(SystemCheckService.Status.WARNING);
            assertThat(check.code()).isEqualTo("AGENT_MODEL_CONFIGURED");
        });
        assertThat(content).doesNotContain("private-", "PrivateAlice", "example.invalid", "127.0.0.1");
        assertThat(counts()).isEqualTo(before);
        assertThat(REQUESTS).hasValue(0);
    }

    @Test
    void enabledButInvalidModelConfigurationIsRedactedAndDoesNotCallProvider() throws Exception {
        String original = assist.getEndpoint();
        try {
            assist.setEndpoint("https://private-user:private-secret@private-model.example.invalid/");
            String content = mvc.perform(get("/api/v1/system/checks")
                            .header("Authorization", "Bearer " + auth.login("demo", "admin", "demo").token()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json.read(content, SystemCheckService.Report.class).checks())
                    .filteredOn(check -> check.id().equals("model")).singleElement().satisfies(check -> {
                        assertThat(check.status()).isEqualTo(SystemCheckService.Status.DOWN);
                        assertThat(check.code()).isEqualTo("CHECK_FAILED");
                    });
            assertThat(content).doesNotContain("private-", "example.invalid");
            assertThat(REQUESTS).hasValue(0);
        } finally { assist.setEndpoint(original); }
    }

    private List<Integer> counts() {
        return TABLES.stream().map(table -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).toList();
    }

    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) { throw new IllegalStateException("Cannot start diagnostics fixture", failure); }
    }
}
