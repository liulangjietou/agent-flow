package io.agentflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实过滤器链与全局异常处理的兜底契约：请求体上限、并发冲突翻译，以及容器错误出口对未知异常和依赖故障的稳定错误码与追踪标识。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:api-resilience;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=true", "agentflow.webhooks.worker-enabled=false",
        "agentflow.web.max-request-body-bytes=1024"})
@AutoConfigureMockMvc
@Import(ApiResilienceIntegrationTest.Probe.class)
class ApiResilienceIntegrationTest {
    private static final String PATH = "/api/v1/resilience-test";
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;

    @Test
    void declaredOversizedBodyIsRejectedBeforeReachingTheController() throws Exception {
        byte[] body = ("{\"value\":\"" + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
        var response = mvc.perform(post(PATH + "/echo").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertError(response, 413, "REQUEST_BODY_TOO_LARGE");
    }

    @Test
    void undeclaredOversizedBodyIsRejectedWhileStreaming() throws Exception {
        byte[] body = ("{\"value\":\"" + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
        var response = mvc.perform(post(PATH + "/echo").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content(body).with(request -> {
                    // 模拟分块传输：不声明 Content-Length，只能在读取时截断。
                    request.removeHeader("Content-Length");
                    return new org.springframework.mock.web.MockHttpServletRequest(request.getServletContext()) {
                        { setMethod(request.getMethod()); setRequestURI(request.getRequestURI());
                          setContentType(request.getContentType()); setContent(body);
                          addHeader("Authorization", request.getHeader("Authorization")); }
                        @Override public long getContentLengthLong() { return -1; }
                        @Override public int getContentLength() { return -1; }
                    };
                })).andReturn().getResponse();
        assertError(response, 413, "REQUEST_BODY_TOO_LARGE");
    }

    @Test
    void bodyWithinLimitStillReachesTheController() throws Exception {
        var response = mvc.perform(post(PATH + "/echo").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"ok\"}")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("ok");
    }

    @Test
    void translatedDatabaseConcurrencyFailuresBecomeConflicts() throws Exception {
        for (String path : new String[]{"/deadlock", "/lock"}) {
            assertError(mvc.perform(get(PATH + path).header("Authorization", token())).andReturn().getResponse(),
                    409, "CONCURRENCY_CONFLICT");
        }
    }

    @Test
    void databaseOutageIsReportedAsDependencyUnavailableWithoutLeakingDetails() throws Exception {
        var response = mvc.perform(get("/error")
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION, new jakarta.servlet.ServletException(
                        new DataAccessResourceFailureException("connect private-database-host refused"))))
                .andReturn().getResponse();
        assertError(response, 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(response.getContentAsString()).doesNotContain("private-database-host");
    }

    @Test
    void unexpectedFailureStillPropagatesSoOuterComponentsSeeTheRootCause() {
        // MVC 层不吞未知异常：幂等与事务外层需要看到真实失败。
        assertThatThrownBy(() -> mvc.perform(get(PATH + "/unexpected").header("Authorization", token())))
                .isInstanceOf(jakarta.servlet.ServletException.class).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void containerErrorDispatchKeepsTheStableContractWithoutLeakingDetails() throws Exception {
        // MockMvc 不执行容器错误分派，这里按 Servlet 规范设置错误属性直接请求 /error。
        var response = mvc.perform(get("/error")
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, PATH + "/unexpected")
                .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private-internal-state")))
                .andReturn().getResponse();
        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.getContentAsString()).doesNotContain("private-internal-state").contains(PATH + "/unexpected");
        assertError(mvc.perform(get("/error").requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 405))
                .andReturn().getResponse(), 405, "INVALID_REQUEST");
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        var error = json.read(response.getContentAsString(), JsonNode.class);
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("traceId").asText()).isNotBlank();
    }

    private String token() { return "Bearer " + auth.login("demo", "alice", "demo").token(); }

    /**
     * 仅测试上下文可用的探针，抛出真实的 Spring 翻译异常。
     * @author owlzhangfq@gmail.com
     */
    @RestController
    static class Probe {
        @PostMapping(PATH + "/echo") public Map<String, Object> echo(@RequestBody Map<String, Object> body) { return body; }
        @GetMapping(PATH + "/deadlock") public void deadlock() {
            throw new DeadlockLoserDataAccessException("deadlock", new java.sql.SQLException("deadlock", "40P01"));
        }
        @GetMapping(PATH + "/lock") public void lock() { throw new CannotAcquireLockException("lock timeout"); }
        @GetMapping(PATH + "/unexpected") public void unexpected() { throw new IllegalStateException("private-internal-state"); }
    }
}
