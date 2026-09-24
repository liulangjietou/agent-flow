package io.agentflow.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import io.agentflow.common.JsonUtil;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/**
 * 后通道注销显式启用，所有认证实例必须使用同一配置和数据库。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentflow.auth.backchannel.enabled", havingValue = "true")
public class OidcBackchannelConfiguration {
    /** 错误认证组合启动即失败；演示和单实例内存会话不会隐式开启企业注销。 */
    @Bean
    public OidcLogoutScopes oidcLogoutScopes(JdbcTemplate jdbc, JsonUtil json, OidcProperties oidc,
            PlatformTransactionManager manager, @Value("${agentflow.auth.demo-enabled:false}") boolean demo,
            @Value("${agentflow.auth.session.jdbc-enabled:false}") boolean shared) {
        if (!oidc.enabled() || demo || !shared) {
            throw new IllegalArgumentException("Back-channel logout requires enterprise OIDC and shared JDBC sessions");
        }
        return new OidcLogoutScopes(jdbc, json, oidc, manager);
    }

    /** 签名算法与当前 ID Token 接入一致；只从已验证发现配置中的公钥端点读取。 */
    @Bean
    public NimbusJwtDecoder oidcLogoutDecoder(ClientRegistrationRepository clients) {
        var client = clients.findByRegistrationId(OidcClientConfiguration.REGISTRATION_ID);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        var decoder = NimbusJwtDecoder.withJwkSetUri(client.getProviderDetails().getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory))
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(null, JOSEObjectType.JWT, new JOSEObjectType("logout+jwt"))))
                .build();
        // 保留原始标识和受众类型，避免默认转换器把数字等错误声明转成可接受的字符串。
        decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(Map.of(
                "iss", value -> value, "sub", value -> value, "aud", value -> value, "jti", value -> value)));
        decoder.setJwtValidator(new OidcLogoutTokenValidator(client.getProviderDetails().getIssuerUri(),
                client.getClientId(), Clock.systemUTC()));
        return decoder;
    }
}
