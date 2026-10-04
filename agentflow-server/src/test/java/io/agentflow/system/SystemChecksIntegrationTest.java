package io.agentflow.system;

import io.agentflow.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证容器探针的最小公开范围与管理员自检的真实依赖结果。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:system-checks;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true"
})
@AutoConfigureMockMvc
class SystemChecksIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"})
    void probeAllowsOnlyMinimalAnonymousRead(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UP"))
                .andExpect(jsonPath("components").doesNotExist())
                .andExpect(jsonPath("details").doesNotExist());
        mvc.perform(head(path)).andExpect(status().isOk());
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator/health/db", "/actuator/health/readiness/extra", "/api/v1/system/checks"})
    void otherPathsStillRequireAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAreDisabledEvenForBusinessAdministrators() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus").header("Authorization", token("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void businessApproverCannotInspectDeployment() throws Exception {
        mvc.perform(get("/api/v1/system/checks").header("Authorization", token("manager")))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorSeesRealChecksAndDisabledAdapters() throws Exception {
        mvc.perform(get("/api/v1/system/checks").header("Authorization", token("admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("checkedAt").isNotEmpty())
                .andExpect(jsonPath("checks.length()").value(11))
                .andExpect(jsonPath("checks[?(@.id == 'database')].status").value("UP"))
                .andExpect(jsonPath("checks[?(@.id == 'migrations')].status").value("UP"))
                .andExpect(jsonPath("checks[?(@.id == 'flowable')].status").value("UP"))
                .andExpect(jsonPath("checks[?(@.id == 'templates')].status").value("UP"))
                .andExpect(jsonPath("checks[?(@.id == 'authentication')].code").value("DEMO_AUTH_ONLY"))
                .andExpect(jsonPath("checks[?(@.id == 'notifications')].code").value("NOTIFICATION_WORKER_DISABLED"))
                .andExpect(jsonPath("checks[?(@.id == 'sessionStorage')].code").value("JDBC_SESSIONS_DISABLED"))
                .andExpect(jsonPath("checks[?(@.id == 'organization')].code").value("LOCAL_ORGANIZATION_NOT_INITIALIZED"))
                .andExpect(jsonPath("checks[?(@.id == 'organizationSync')].code").value("ORGANIZATION_SYNC_DISABLED"))
                .andExpect(jsonPath("checks[?(@.id == 'objectStorage')].code").value("ATTACHMENT_STORAGE_NOT_CONFIGURED"))
                .andExpect(jsonPath("checks[?(@.id == 'model')].status").value("WARNING"))
                .andExpect(jsonPath("checks[?(@.id == 'model')].code").value("AGENT_MODEL_DISABLED"))
                .andExpect(jsonPath("checks[?(@.status == 'NOT_IMPLEMENTED')]").value(org.hamcrest.Matchers.empty()));
    }

    private String token(String username) {
        return "Bearer " + auth.login("demo", username, "demo").token();
    }
}
