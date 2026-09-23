package io.agentflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 浏览器跨域预检先于认证，实际业务请求仍要求令牌且只接受配置来源。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:web-cors;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true", "agentflow.web.allowed-origin=http://127.0.0.1:5175"})
@AutoConfigureMockMvc
class WebCorsIntegrationTest {
    @Autowired MockMvc mvc;
    private static final String ORIGIN = "http://127.0.0.1:5175";

    @Test
    void browserCanPreflightLoginAndAuthenticatedWritesWithoutSendingAToken() throws Exception {
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/applications/any/comments"}) {
            mvc.perform(options(path).header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,authorization,idempotency-key"))
                    .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void actualMissingOrInvalidTokensStillReturnReadable401ToAllowedOrigin() throws Exception {
        mvc.perform(get("/api/v1/applications").header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHENTICATED"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(get("/api/v1/applications").header("Origin", ORIGIN).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(options("/api/v1/applications")) .andExpect(status().isUnauthorized());
    }

    @Test
    void otherOriginsAndUnsupportedMethodsOrHeadersAreRejectedBeforeBusinessActions() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://other.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://other.example").contentType("application/json")
                        .content("{\"tenantId\":\"demo\",\"username\":\"alice\",\"password\":\"demo\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/applications").header("Origin", ORIGIN).header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/applications").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-unsupported")) .andExpect(status().isForbidden());
    }
}
