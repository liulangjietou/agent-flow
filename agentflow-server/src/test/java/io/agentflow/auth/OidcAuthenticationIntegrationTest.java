package io.agentflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.JsonUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实签名与授权码交换验证企业认证；同时穿过原请求身份和业务资源授权边界。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:oidc-auth;DB_CLOSE_DELAY=-1",
        "agentflow.auth.demo-enabled=false", "agentflow.auth.oidc.enabled=true",
        "agentflow.auth.oidc.client-id=platform", "agentflow.auth.oidc.client-secret=fixture-secret",
        "agentflow.auth.oidc.tenant-claim=tenant", "agentflow.auth.oidc.roles-claim=roles",
        "agentflow.auth.oidc.tenant-mappings.external=tenant-a", "agentflow.auth.oidc.tenant-mappings.external-b=tenant-b",
        "agentflow.auth.oidc.role-mappings.staff[0]=EMPLOYEE", "agentflow.auth.oidc.allow-insecure-loopback=true",
        "agentflow.web.allowed-origin=http://localhost", "agentflow.finance-gateway.enabled=true",
        "agentflow.finance-gateway.tenants.demo.endpoint=http://127.0.0.1:12345/finance",
        "agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback=true",
        "agentflow.payment-callbacks.enabled=true", "agentflow.payment-callbacks.worker-enabled=false",
        "agentflow.events.enabled=true", "agentflow.events.worker-enabled=false"})
@AutoConfigureMockMvc
class OidcAuthenticationIntegrationTest {
    static final OidcTestProvider provider = new OidcTestProvider();
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired io.agentflow.approval.repository.ApplicationRepository applications;
    @Autowired org.springframework.context.ApplicationContext context;

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("agentflow.auth.oidc.issuer", provider::issuer);
        registry.add("agentflow.payment-callbacks.tenants.demo.signing-secrets[0]", () -> io.agentflow.finance.callback.PaymentCallbackTestRequests.SECRET);
        registry.add("agentflow.events.sources.erp.tenant-id", () -> "demo");
        registry.add("agentflow.events.sources.erp.source-key", () -> "erp");
        registry.add("agentflow.events.sources.erp.trust-revision", () -> 1);
        registry.add("agentflow.events.sources.erp.enabled", () -> true);
        registry.add("agentflow.events.sources.erp.signing-secrets[0]", () -> io.agentflow.event.EventTestRequests.SECRET);
        registry.add("spring.datasource.url", () -> System.getProperty("agentflow.oidc-test.jdbc-url", "jdbc:h2:mem:oidc-auth;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.driver-class-name", () -> System.getProperty("agentflow.oidc-test.jdbc-driver", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getProperty("agentflow.oidc-test.jdbc-user", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("agentflow.oidc-test.jdbc-password", ""));
    }
    @AfterAll static void closeProvider() { provider.close(); }
    @BeforeEach void reset() { provider.mode = "valid"; provider.subject = "employee-42"; provider.tenant = "external"; }

    @Test void callbackPostUsesSignatureWhileManagementStillRequiresSessionAndCsrf() throws Exception {
        String path = io.agentflow.finance.callback.PaymentCallbackVerifier.PATH;
        mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("PAYMENT_CALLBACK_UNAUTHENTICATED"));
        String body = json.write(new io.agentflow.finance.callback.PaymentCallbackVerifier.Signal(1, "payment.changed", "demo",
                io.agentflow.finance.callback.PaymentCallbackVerifier.Kind.EMPLOYEE, java.util.UUID.randomUUID(), "a".repeat(64), 1));
        mvc.perform(io.agentflow.finance.callback.PaymentCallbackTestRequests.request("evt_oidc", body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/" + java.util.UUID.randomUUID() + "/retry").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
    }

    @Test void signatureCallbackRequiresReceiptAuthenticationAndDoesNotExposeAdjacentWritePaths() throws Exception {
        String path = io.agentflow.signature.SignatureCallbackVerifier.PATH;
        mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("SIGNATURE_CALLBACK_UNAUTHENTICATED"));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        String operations = "/api/v1/applications/" + java.util.UUID.randomUUID() + "/signatures";
        for (String endpoint : java.util.List.of(path + "/", path + "/retry", operations, operations + "/" + java.util.UUID.randomUUID() + "/cancel"))
            mvc.perform(post(endpoint).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
    }

    @Test
    void eventReceptionRequiresSignatureAndRecoveryKeepsSessionCsrfProtection() throws Exception {
        String path = io.agentflow.event.EventIngressVerifier.PATH;
        mvc.perform(post(path).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("EVENT_UNAUTHENTICATED"));
        String body = json.write(new io.agentflow.event.EventSignal(1, "demo", "erp", "GoodsAccepted", java.util.UUID.randomUUID(), 1, "wait", "accepted", 1));
        mvc.perform(io.agentflow.event.EventTestRequests.request(body, "evt_oidc_event", Instant.now()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/" + java.util.UUID.randomUUID() + "/retry").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
    }

    @Test
    void realCodeExchangeMapsOnlyExplicitIdentityAndRotatesSession() throws Exception {
        assertThat(context.getBeansOfType(org.springframework.session.SessionRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(OidcLogoutScopes.class)).isEmpty();
        LoginAttempt attempt = authorize();
        String previousId = attempt.session().getId();
        MockHttpSession session = complete(attempt);
        assertThat(session.getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/v1/auth/me").session(session).header("Authorization", "Bearer forged"))
                .andExpect(status().isOk()).andExpect(jsonPath("actor.tenantId").value("tenant-a"))
                .andExpect(jsonPath("actor.userId").value("employee-42"))
                .andExpect(jsonPath("actor.roles[0]").value("EMPLOYEE")).andExpect(jsonPath("token").doesNotExist());
        mvc.perform(get("/api/v1/system/checks").session(session)).andExpect(status().isForbidden());
        assertThat(Collections.list(session.getAttributeNames())).noneMatch(name -> name.contains("AUTHORIZED_CLIENT"));
        mvc.perform(get(attempt.callback()).session(session)).andExpect(redirectedUrl("http://localhost/?authError=oidc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonce", "signature", "issuer", "audience", "expired", "unmappedTenant", "unmappedRole"})
    void rejectsUntrustedTokenAndUnmappedIdentity(String mode) throws Exception {
        provider.mode = mode;
        LoginAttempt attempt = authorize();
        mvc.perform(get(attempt.callback()).session(attempt.session()))
                .andExpect(redirectedUrl("http://localhost/?authError=oidc"));
        assertThat(attempt.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedStateNeverReachesTokenEndpointAndRedirectIgnoresHost() throws Exception {
        LoginAttempt attempt = authorize();
        int before = provider.exchanges;
        Map<String, String> values = OidcTestProvider.parameters(attempt.callback());
        mvc.perform(get("/api/v1/auth/oidc/callback/enterprise").session(attempt.session())
                .param("code", values.get("code")).param("state", "forged").header("Host", "attacker.invalid"))
                .andExpect(redirectedUrl("http://localhost/?authError=oidc"));
        assertThat(provider.exchanges).isEqualTo(before);
    }

    @Test
    void csrfProtectsMutationsAndLogoutThenOldSessionCannotBeUsed() throws Exception {
        MockHttpSession session = complete(authorize());
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("code").value("CSRF_INVALID"));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/applications").session(session).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
        JsonNode options = options(session);
        mvc.perform(post("/api/v1/auth/logout").session(session).header(options.path("csrfHeader").asText(), options.path("csrfToken").asText()))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("AGENTFLOW_SESSION", 0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void binaryAttachmentUploadStillRequiresCsrfAndTheOriginalSessionActor() throws Exception {
        MockHttpSession session = complete(authorize());
        String path = "/api/v1/applications/" + java.util.UUID.randomUUID() + "/attachments/" + java.util.UUID.randomUUID() + "/content";
        mvc.perform(put(path).session(session).header("X-Application-Version", "1")
                        .contentType("application/octet-stream").content(new byte[]{1, 2}))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF_INVALID"));
        JsonNode options = options(session);
        String identity = java.net.URLEncoder.encode("[\"tenant-a\",\"other-user\"]", java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(put(path).session(session).header("X-Application-Version", "1")
                        .header("X-CSRF-TOKEN", options.path("csrfToken").asText()).header("X-AgentFlow-Actor", identity)
                        .contentType("application/octet-stream").content(new byte[]{1, 2}))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHENTICATED"));
        identity = java.net.URLEncoder.encode("[\"tenant-a\",\"employee-42\"]", java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(put(path).session(session).header("X-Application-Version", "1")
                        .header("X-CSRF-TOKEN", options.path("csrfToken").asText()).header("X-AgentFlow-Actor", identity)
                        .contentType("application/octet-stream").content(new byte[]{1, 2}))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
    }

    @Test
    void providerWithoutLogoutMetadataKeepsLocalSessionWhenGlobalLogoutIsRequested() throws Exception {
        MockHttpSession session = complete(authorize());
        JsonNode options = options(session);
        assertThat(options.path("providerLogoutUrl").isMissingNode() || options.path("providerLogoutUrl").isNull()).isTrue();
        mvc.perform(post(OidcProviderLogoutFilter.PATH).session(session)
                        .param("_csrf", options.path("csrfToken").asText()).param("actor", "[\"tenant-a\",\"employee-42\"]"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void expiredSessionIsRejectedEvenIfSecurityContextRemains() throws Exception {
        MockHttpSession session = complete(authorize());
        SecurityContext context = (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        PlatformOidcUser user = (PlatformOidcUser) context.getAuthentication().getPrincipal();
        var token = new OidcIdToken(user.idToken().getTokenValue(), Instant.now().minusSeconds(60), Instant.now().minusSeconds(1), user.getClaims());
        var expired = new PlatformOidcUser(token, user.tenantId(), user.userId(), user.roles());
        context.setAuthentication(new OAuth2AuthenticationToken(expired, expired.getAuthorities(), "enterprise"));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void cookieAccountSwitchCannotSubmitOrReadAnOldPagesBoundRequests() throws Exception {
        MockHttpSession session = complete(authorize());
        JsonNode options = options(session);
        String oldIdentity = java.net.URLEncoder.encode("[\"tenant-a\",\"other-user\"]", java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/applications").session(session)
                        .header("X-CSRF-TOKEN", options.path("csrfToken").asText()).header("X-AgentFlow-Actor", oldIdentity)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/applications").session(session).header("X-AgentFlow-Actor", oldIdentity))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void mappedIdentityRetainsTenantAndApplicationVisibilityAndMutationRules() throws Exception {
        var id = java.util.UUID.randomUUID();
        applications.save(io.agentflow.approval.model.Application.draft(id, "tenant-a", "OIDC-" + id,
                "fixture", 1, "employee-42", "会话权限验收", Map.of()));
        MockHttpSession owner = complete(authorize());
        String path = "/api/v1/applications/" + id;
        mvc.perform(get(path).session(owner)).andExpect(status().isOk());
        provider.subject = "other-user";
        MockHttpSession unrelated = complete(authorize());
        mvc.perform(get(path).session(unrelated)).andExpect(status().isNotFound());
        provider.subject = "employee-42"; provider.tenant = "external-b";
        MockHttpSession otherTenant = complete(authorize());
        mvc.perform(get(path).session(otherTenant)).andExpect(status().isNotFound());
        JsonNode options = options(owner);
        String expectedActor = java.net.URLEncoder.encode("[\"tenant-a\",\"employee-42\"]", java.nio.charset.StandardCharsets.UTF_8);
        String key = java.util.UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) mvc.perform(post(path + "/cancel").session(owner)
                        .header("X-CSRF-TOKEN", options.path("csrfToken").asText()).header("X-AgentFlow-Actor", expectedActor)
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"expectedVersion\":1,\"comment\":\"验收作废\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"))
                .andExpect(jsonPath("version").value(2));
    }

    private JsonNode options(MockHttpSession session) throws Exception {
        return json.read(mvc.perform(get("/api/v1/auth/options").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("mode").value("OIDC"))
                .andReturn().getResponse().getContentAsString(), JsonNode.class);
    }

    private LoginAttempt authorize() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/oidc/authorize/enterprise")
                .header("Host", "attacker.invalid").param("returnUrl", "https://attacker.invalid"))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI location = URI.create(result.getResponse().getRedirectedUrl());
        assertThat(OidcTestProvider.parameters(location).get("redirect_uri")).isEqualTo("http://localhost/api/v1/auth/oidc/callback/enterprise");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(location).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        return new LoginAttempt((MockHttpSession) result.getRequest().getSession(false), URI.create(response.headers().firstValue("location").orElseThrow()));
    }

    private MockHttpSession complete(LoginAttempt attempt) throws Exception {
        MvcResult result = mvc.perform(get(attempt.callback()).session(attempt.session()))
                .andExpect(redirectedUrl("http://localhost/")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** 授权请求的本地会话和身份服务实际返回地址。
     * @author owlzhangfq@gmail.com
     */
    private record LoginAttempt(MockHttpSession session, URI callback) { }
}
