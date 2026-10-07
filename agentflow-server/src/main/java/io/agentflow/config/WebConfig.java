package io.agentflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.List;

/**
 * 在认证前核对唯一允许的前端来源，使预检及认证失败响应都遵守同一 CORS 规则。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class WebConfig {
    private final String allowedOrigin;

    /** 读取前端来源配置。 */
    public WebConfig(@Value("${agentflow.web.allowed-origin:http://localhost:5173}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    /** 预检不执行业务，实际请求继续进入 Bearer 认证；不开放通配来源或 Cookie 凭据。 */
    @Bean
    public FilterRegistrationBean<CorsFilter> apiCorsFilter() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-CSRF-TOKEN", "X-AgentFlow-Actor", "X-Application-Version"));
        configuration.setExposedHeaders(List.of(io.agentflow.observability.DiagnosticContext.HEADER));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
