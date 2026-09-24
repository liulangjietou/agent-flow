package io.agentflow.auth;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * 企业多实例共用 JDBC 会话；持久化属于认证基础设施，不进入审批领域事务。
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agentflow.auth.session.jdbc-enabled", havingValue = "true")
@EnableJdbcHttpSession(tableName = "AF_HTTP_SESSION")
public class JdbcSessionConfiguration {
    /** 复用平台空闲超时配置；错误的认证组合在启动时失败。 */
    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionRepositorySettings(
            OidcProperties oidc, ServerProperties server,
            @Value("${agentflow.auth.demo-enabled:false}") boolean demoEnabled) {
        if (!oidc.enabled() || demoEnabled) {
            throw new IllegalArgumentException("JDBC sessions require enterprise OIDC with demo authentication disabled");
        }
        Duration timeout = server.getServlet().getSession().getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.getSeconds() < 1
                || timeout.getSeconds() > Integer.MAX_VALUE || timeout.getNano() != 0) {
            throw new IllegalArgumentException("Session timeout must be a positive whole number of seconds");
        }
        return repository -> repository.setDefaultMaxInactiveInterval(timeout);
    }

    /** 在安全链读取身份之前恢复共享会话，并覆盖异步和错误转发。 */
    @Bean
    public FilterRegistrationBean<SessionRepositoryFilter<?>> sessionFilterRegistration(SessionRepositoryFilter<?> filter) {
        var registration = new FilterRegistrationBean<SessionRepositoryFilter<?>>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }

    /** 共享会话保持现有 Cookie 名称和 HTTPS 属性，不接受请求提供的域名配置。 */
    @Bean
    public CookieSerializer sharedSessionCookie(@Value("${agentflow.web.allowed-origin:http://localhost:5173}") String origin) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName(OidcClientConfiguration.SESSION_COOKIE);
        // 与原容器 Cookie 的原始标识编码一致；旧值只会查无会话，不解码成非法数据库字符。
        cookie.setUseBase64Encoding(false);
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie("https".equals(URI.create(origin).getScheme()));
        cookie.setSameSite("Lax");
        return cookie;
    }
}
