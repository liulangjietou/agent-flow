package io.agentflow.auth;

import io.agentflow.common.JsonUtil;
import io.agentflow.finance.callback.PaymentCallbackVerifier;
import io.agentflow.event.EventIngressVerifier;
import io.agentflow.signature.SignatureCallbackVerifier;
import java.time.Instant;
import java.util.Map;
import io.agentflow.observability.RequestTrace;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录协议在接入层完成，资源授权仍由 CurrentActor 下游的应用服务负责。
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(OidcProperties.class)
public class AuthenticationConfiguration {
    /** 演示令牌保持无会话模式；企业登录启用标准授权码、PKCE、会话和 CSRF 防护。 */
    @Bean
    public SecurityFilterChain authenticationChain(HttpSecurity http, OidcProperties properties,
            ObjectProvider<ClientRegistrationRepository> clients, JsonUtil json,
            ObjectProvider<OidcLogoutScopes> logoutScopes,
            @Qualifier("oidcLogoutDecoder") ObjectProvider<JwtDecoder> logoutDecoders,
            @Value("${agentflow.web.allowed-origin:http://localhost:5173}") String origin) throws Exception {
        http.authorizeHttpRequests(access -> access.anyRequest().permitAll())
                .requestCache(cache -> cache.disable()).httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        if (!properties.enabled()) {
            http.csrf(csrf -> csrf.disable()).logout(logout -> logout.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            return http.build();
        }
        var repository = clients.getObject();
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(repository, OidcClientConfiguration.AUTHORIZATION_BASE);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var mapper = new OidcActorMapper(properties);
        var revocations = logoutScopes.getIfAvailable();
        if (revocations != null) {
            http.addFilterBefore(new OidcBackchannelLogoutFilter(logoutDecoders.getObject(), revocations, json), CsrfFilter.class);
        }
        String home = origin.replaceAll("/$", "") + "/";
        var providerLogout = new OidcProviderLogoutFilter(repository, json, home);
        http.addFilterBefore(providerLogout, LogoutFilter.class);
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).ignoringRequestMatchers(PaymentCallbackVerifier::matches, EventIngressVerifier::matches, SignatureCallbackVerifier::matches))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(json.write(Map.of("code", "CSRF_INVALID", "message", "Refresh the session before retrying",
                            "traceId", RequestTrace.id(request), "path", request.getRequestURI())));
                }))
                .oauth2Login(login -> login.clientRegistrationRepository(repository)
                        .authorizedClientRepository(new DiscardingAuthorizedClientRepository()).loginPage(home)
                        .authorizationEndpoint(endpoint -> {
                            endpoint.authorizationRequestResolver(resolver);
                            if (revocations != null) endpoint.authorizationRequestRepository(
                                    new LogoutAwareAuthorizationRequestRepository(revocations));
                        })
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(OidcClientConfiguration.CALLBACK_BASE + "/*"))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(request -> {
                            // 签名、签发方、受众和 nonce 由 Spring 协议链路校验；这里仅映射签名声明。
                            var actor = mapper.map(request.getIdToken(), Instant.now());
                            return new PlatformOidcUser(request.getIdToken(), actor.tenantId(), actor.userId(), actor.roles());
                        }))
                        .successHandler((request, response, authentication) -> {
                            if (revocations != null) {
                                Long order = (Long) request.getAttribute(LogoutAwareAuthorizationRequestRepository.CALLBACK_ORDER);
                                try {
                                    revocations.requireActive(((PlatformOidcUser) authentication.getPrincipal()).getIdToken(), order);
                                    request.getSession().setAttribute(OidcLogoutScopes.SESSION_ORDER, order);
                                } catch (OAuth2AuthenticationException revoked) {
                                    request.getSession().invalidate();
                                    SecurityContextHolder.clearContext();
                                    response.sendRedirect(home + "?authError=oidc");
                                    return;
                                }
                            }
                            response.sendRedirect(home);
                        })
                        .failureHandler((request, response, exception) -> {
                            var session = request.getSession(false);
                            if (session != null) session.invalidate();
                            response.sendRedirect(home + "?authError=oidc");
                        }))
                .logout(logout -> logout.logoutRequestMatcher(request -> "POST".equals(request.getMethod())
                                && ("/api/v1/auth/logout".equals(request.getRequestURI())
                                || OidcProviderLogoutFilter.PATH.equals(request.getRequestURI())))
                        .deleteCookies(OidcClientConfiguration.SESSION_COOKIE)
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (OidcProviderLogoutFilter.PATH.equals(request.getRequestURI())) {
                                providerLogout.complete(request, response, authentication);
                            } else response.setStatus(204);
                        }));
        return http.build();
    }
}
