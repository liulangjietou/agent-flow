package io.agentflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实过滤器链验证响应、错误和已认证上下文共用追踪标识，不相信传入身份。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:request-trace;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.webhooks.worker-enabled=false"})
@AutoConfigureMockMvc
@Import(RequestTraceIntegrationTest.Probe.class)
class RequestTraceIntegrationTest {
    private static final String HEADER = "X-Trace-Id";
    private static final String PATH = "/api/v1/trace-test";
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuthService auth;
    @Autowired JsonUtil json;

    @AfterEach void clear() { MDC.clear(); }

    @Test
    void unavailableAuthenticationStorageStillReturnsOneSafeTrace() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-storage-error"))
                .when(auth).authenticate("unavailable");
        var response = mvc.perform(get(PATH).header("Authorization", "Bearer unavailable"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertTrace(response.getHeader(HEADER));
        var error = json.read(response.getContentAsString(), JsonNode.class);
        assertThat(error.path("traceId").asText()).isEqualTo(response.getHeader(HEADER));
        assertThat(error.path("code").asText()).isEqualTo("AUTHENTICATION_UNAVAILABLE");
        assertThat(response.getContentAsString()).doesNotContain("private-storage-error");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void trustedTenantAndServerTraceReachTheControllerWithoutTrustingInboundHeaders() throws Exception {
        String supplied = UUID.randomUUID().toString();
        var response = mvc.perform(get(PATH).header("Authorization", token()).header(HEADER, supplied)
                        .header("X-Tenant-Id", "forged").header("businessNo", "private-value"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String trace = response.getHeader(HEADER);
        assertTrace(trace);
        assertThat(trace).isNotEqualTo(supplied);
        var context = json.read(response.getContentAsString(), JsonNode.class);
        assertThat(context.path("traceId").asText()).isEqualTo(trace);
        assertThat(context.path("tenantId").asText()).isEqualTo("demo");
        assertThat(context.path("businessNo").asText()).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void authenticationBindingAndDomainErrorsMatchTheirResponseHeaderAndNeverReuseAnotherRequest() throws Exception {
        String previous = null;
        for (String path : new String[]{PATH, PATH + "/binding", PATH + "/domain"}) {
            var request = get(path);
            if (!path.equals(PATH)) request.header("Authorization", token());
            var response = mvc.perform(request).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(path.equals(PATH) ? 401 : path.endsWith("binding") ? 400 : 404);
            String trace = response.getHeader(HEADER);
            assertTrace(trace);
            assertThat(trace).isNotEqualTo(previous);
            assertThat(json.read(response.getContentAsString(), JsonNode.class).path("traceId").asText()).isEqualTo(trace);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            previous = trace;
        }
    }

    @Test
    void corsRejectionsAndAllowedResponsesBothCarryTraceWithoutTrustingAClientValue() throws Exception {
        var rejected = mvc.perform(options(PATH).header("Origin", "https://untrusted.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertTrace(rejected.getHeader(HEADER));
        var allowed = mvc.perform(get(PATH).header("Authorization", token()).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrace(allowed.getHeader(HEADER));
        assertThat(allowed.getHeader("Access-Control-Expose-Headers")).contains(HEADER);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private String token() { return "Bearer " + auth.login("demo", "alice", "demo").token(); }
    private static void assertTrace(String value) { assertThat(value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }

    /**
     * 仅测试上下文可用的探针，运行真实认证与异常处理。
     * @author owlzhangfq@gmail.com
     */
    @RestController
    static class Probe {
        @GetMapping(PATH) public Map<String, String> context() {
            var values = MDC.getCopyOfContextMap();
            return values == null ? Map.of() : values;
        }
        @GetMapping(PATH + "/binding") public int binding(@RequestParam int count) { return count; }
        @GetMapping(PATH + "/domain") public void domain() { throw new DomainException("NOT_FOUND", "Resource not found"); }
    }
}
