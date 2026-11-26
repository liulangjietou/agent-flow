package io.agentflow.system;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.auth.TotpAuthenticator;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证第三阶段云原生与企业生态核心能力：本地口令+TOTP MFA 认证、多租户动态 OIDC 配置、
 * 审计分批留存归档与流式 CSV 大数据导出。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "agentflow.auth.demo-enabled=false",
        "agentflow.sla.reminders-enabled=false",
        "agentflow.audit.retention-enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class Phase3CloudEcosystemIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:phase3-cloud-eco;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthService auth;

    private String tenantId;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setup() {
        tenantId = "p3-" + UUID.randomUUID();
        Actor admin = new Actor(tenantId, "p3admin", Set.of("ADMIN", "APPROVER", "EMPLOYEE"));
        Actor employee = new Actor(tenantId, "p3emp", Set.of("EMPLOYEE"));
        adminToken = "tok-admin-" + UUID.randomUUID();
        userToken = "tok-emp-" + UUID.randomUUID();
        doReturn(admin).when(auth).authenticate(adminToken);
        doReturn(employee).when(auth).authenticate(userToken);
    }

    @Test
    void localPasswordAndTotpMfaFullLifecycleWithLockoutAndSessionRevocation() throws Exception {
        // 1. 管理员创建本地账号
        var created = json.read(mvc.perform(post("/api/v1/auth/local-users")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "username", "alice.local",
                                "displayName", "本地用户Alice",
                                "password", "SecurePass2026!",
                                "roles", List.of("EMPLOYEE", "APPROVER")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(created.path("username").asText()).isEqualTo("alice.local");
        assertThat(created.path("mfaEnabled").asBoolean()).isFalse();

        // 2. 口令登录签发持久化 Bearer 令牌，并通过 BearerAuthFilter 访问 /api/v1/auth/me
        var loginResp = json.read(mvc.perform(post("/api/v1/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "tenantId", tenantId,
                                "username", "alice.local",
                                "password", "SecurePass2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        String localToken = loginResp.path("token").asText();
        assertThat(localToken).startsWith("afl_");

        var meResp = json.read(mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + localToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(meResp.path("actor").path("userId").asText()).isEqualTo("alice.local");

        // 3. 初始化并启用 TOTP MFA
        var enrollResp = json.read(mvc.perform(post("/api/v1/auth/local/mfa/enroll")
                        .header("Authorization", "Bearer " + localToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        String secret = enrollResp.path("secret").asText();
        assertThat(enrollResp.path("otpauthUri").asText()).startsWith("otpauth://totp/");

        String totpCode = TotpAuthenticator.codeAt(secret, Instant.now());
        var enabledResp = json.read(mvc.perform(post("/api/v1/auth/local/mfa/enable")
                        .header("Authorization", "Bearer " + localToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("totpCode", totpCode))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(enabledResp.path("mfaEnabled").asBoolean()).isTrue();

        // 4. 开启 MFA 后缺少或错误动态码均拒绝，正确动态码允许登录
        var missingMfa = json.read(mvc.perform(post("/api/v1/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "tenantId", tenantId,
                                "username", "alice.local",
                                "password", "SecurePass2026!"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(missingMfa.path("code").asText()).isEqualTo("MFA_CODE_REQUIRED");

        var mfaLogin = json.read(mvc.perform(post("/api/v1/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "tenantId", tenantId,
                                "username", "alice.local",
                                "password", "SecurePass2026!",
                                "totpCode", TotpAuthenticator.codeAt(secret, Instant.now())))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        String mfaToken = mfaLogin.path("token").asText();

        // 5. 注销后持久化会话立即失效
        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + mfaToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + mfaToken))
                .andExpect(status().isUnauthorized());

        // 6. 连续 5 次错误密码触发账号锁定 (429 ACCOUNT_LOCKED)
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/auth/local/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.write(Map.of(
                                    "tenantId", tenantId,
                                    "username", "alice.local",
                                    "password", "WrongPass0000!"))))
                    .andExpect(status().isUnauthorized());
        }
        var lockedResp = json.read(mvc.perform(post("/api/v1/auth/local/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "tenantId", tenantId,
                                "username", "alice.local",
                                "password", "WrongPass0000!"))))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(lockedResp.path("code").asText()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void tenantOidcProviderCrudValidatesHttpsAndRedactsSecret() throws Exception {
        // 非 HTTPS issuerUri 应拒绝 (422 INVALID_OIDC_PROVIDER)
        var bad = json.read(mvc.perform(put("/api/v1/auth/oidc-providers")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "providerKey", "corp-sso",
                                "displayName", "企业统一认证",
                                "issuerUri", "http://insecure.example.com/oidc",
                                "clientId", "client-1",
                                "clientSecret", "super-secret-value",
                                "scopes", "openid profile email",
                                "enabled", true))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(bad.path("code").asText()).isEqualTo("INVALID_OIDC_PROVIDER");

        // 合法 HTTPS 创建并脱敏密钥
        String raw = mvc.perform(put("/api/v1/auth/oidc-providers")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of(
                                "providerKey", "corp-sso",
                                "displayName", "企业统一认证",
                                "issuerUri", "https://sso.example.com/realms/main",
                                "clientId", "agentflow-web",
                                "clientSecret", "super-secret-value",
                                "scopes", "openid profile email",
                                "enabled", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("super-secret-value");
        JsonNode provider = json.read(raw, JsonNode.class);
        assertThat(provider.path("clientSecretConfigured").asBoolean()).isTrue();
        String providerId = provider.path("id").asText();

        var listed = json.read(mvc.perform(get("/api/v1/auth/oidc-providers")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(listed).hasSize(1);

        mvc.perform(delete("/api/v1/auth/oidc-providers/" + providerId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void auditRetentionArchivesExpiredRowsAndStreamingCsvExportsSafely() throws Exception {
        Instant oldTime = Instant.now().minus(400, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant recentTime = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String oldId = UUID.randomUUID().toString();
        String recentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_event (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, action, actor_id, payload_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                oldId, tenantId, UUID.randomUUID().toString(), "Application", UUID.randomUUID().toString(), 1L,
                "SUBMIT", "alice", "{\"action\":\"SUBMIT\",\"actor\":\"alice\",\"businessNo\":\"OLD-001\"}", Timestamp.from(oldTime));
        jdbc.update("INSERT INTO audit_event (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, action, actor_id, payload_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                recentId, tenantId, UUID.randomUUID().toString(), "Application", UUID.randomUUID().toString(), 1L,
                "=HYPERLINK", "bob", "{\"action\":\"=HYPERLINK\",\"actor\":\"bob\",\"businessNo\":\"NEW-001\"}", Timestamp.from(recentTime));

        // 1. 流式 CSV 导出包含近期记录并对公式开头字符转义防注入
        MvcResult csvAsync = mvc.perform(get("/api/v1/operations/audit/export.csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        String csvContent = mvc.perform(asyncDispatch(csvAsync))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csvContent).startsWith("\uFEFFeventId,occurredAt,source,action");
        assertThat(csvContent).contains("'=HYPERLINK");

        // 非管理员禁止流式导出
        mvc.perform(get("/api/v1/operations/audit/export.csv")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 2. 留存归档拒绝少于 30 天
        mvc.perform(post("/api/v1/operations/audit/retention/run")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("retentionDays", 7))))
                .andExpect(status().isBadRequest());

        // 3. 正常触发 365 天归档：仅旧记录搬迁至 audit_event_archive，近期记录留在热表
        var runResp = json.read(mvc.perform(post("/api/v1/operations/audit/retention/run")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.write(Map.of("retentionDays", 365))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(runResp.path("archivedCount").asInt()).isEqualTo(1);

        var statusResp = json.read(mvc.perform(get("/api/v1/operations/audit/retention")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
        assertThat(statusResp.path("archivedTotal").asLong()).isEqualTo(1L);
        assertThat(statusResp.path("recentRuns")).hasSize(1);

        Integer hotRemaining = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(hotRemaining).isEqualTo(1);
    }
}
