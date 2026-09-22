package io.agentflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 仅允许配置的前端来源访问 API；生产通过部署配置替换默认开发来源。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String allowedOrigin;

    /** 读取前端来源配置。 */
    public WebConfig(@Value("${agentflow.web.allowed-origin:http://localhost:5173}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Idempotency-Key")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
