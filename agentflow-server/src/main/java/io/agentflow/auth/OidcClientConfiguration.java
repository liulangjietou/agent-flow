package io.agentflow.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * 在启动时读取可信签发方元数据并固定回调地址；发现失败不降级为演示登录。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "agentflow.auth.oidc.enabled", havingValue = "true")
public class OidcClientConfiguration {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);
    static final String REGISTRATION_ID = "enterprise";
    static final String AUTHORIZATION_BASE = "/api/v1/auth/oidc/authorize";
    static final String CALLBACK_BASE = "/api/v1/auth/oidc/callback";
    static final String SESSION_COOKIE = "AGENTFLOW_SESSION";

    /** 仅信任配置中的 issuer，限制发现等待时间，并拒绝元数据跳转到其他签发方。 */
    @Bean
    public ClientRegistrationRepository oidcClients(OidcProperties properties, Environment environment,
            @Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled,
            @Value("${agentflow.web.allowed-origin:http://localhost:5173}") String origin) {
        properties.validate(demoEnabled, environment.acceptsProfiles(Profiles.of("prod", "production")), origin);
        var httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(DISCOVERY_TIMEOUT);
        Map<String, Object> metadata = RestClient.builder().requestFactory(factory).build().get()
                .uri(properties.issuer().replaceAll("/$", "") + "/.well-known/openid-configuration")
                .retrieve().body(new ParameterizedTypeReference<>() { });
        if (metadata == null || !properties.issuer().equals(metadata.get("issuer"))) {
            throw new IllegalArgumentException("OIDC discovery issuer does not match configured issuer");
        }
        for (String key : List.of("authorization_endpoint", "token_endpoint", "jwks_uri")) {
            if (!(metadata.get(key) instanceof String value)) throw new IllegalArgumentException("OIDC endpoint is missing");
            properties.requireTrustedUrl(value);
        }
        if (metadata.containsKey(OidcProviderLogoutFilter.END_SESSION_ENDPOINT)) {
            if (!(metadata.get(OidcProviderLogoutFilter.END_SESSION_ENDPOINT) instanceof String value)) {
                throw new IllegalArgumentException("OIDC logout endpoint is invalid");
            }
            properties.requireTrustedUrl(value);
        }
        var client = ClientRegistrations.fromOidcConfiguration(metadata).registrationId(REGISTRATION_ID)
                .clientId(properties.clientId()).clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope("openid").redirectUri(origin.replaceAll("/$", "") + CALLBACK_BASE + "/" + REGISTRATION_ID)
                .clientName("Enterprise identity").build();
        return new InMemoryClientRegistrationRepository(client);
    }

    /** 浏览器仅持有平台会话 Cookie，生产 HTTPS 下始终标记 Secure。 */
    @Bean
    public WebServerFactoryCustomizer<AbstractServletWebServerFactory> oidcSessionCookie(
            @Value("${agentflow.web.allowed-origin:http://localhost:5173}") String origin) {
        return factory -> {
            var cookie = factory.getSession().getCookie();
            cookie.setName(SESSION_COOKIE);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setSecure("https".equals(URI.create(origin).getScheme()));
            cookie.setSameSite(org.springframework.boot.web.server.Cookie.SameSite.LAX);
        };
    }
}
