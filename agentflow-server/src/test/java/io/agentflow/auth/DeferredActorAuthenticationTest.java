package io.agentflow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 原登录复核使用真实 JDBC 会话序列化与退出范围；不以目录或角色快照替代认证事实。
 * @author owlzhangfq@gmail.com
 */
class DeferredActorAuthenticationTest {
    private final Instant now = Instant.now();
    private final AuthService demo = new AuthService(true, "demo");
    private final DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:deferred-auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    private JdbcIndexedSessionRepository sessions;
    private OidcLogoutScopes logouts;
    private final OidcProperties enterprise = properties(true, "EMPLOYEE");

    @BeforeEach void schema() {
        Flyway.configure().dataSource(source).target("23").load().migrate();
        sessions = repository();
        logouts = new OidcLogoutScopes(jdbc, new JsonUtil(new ObjectMapper()), enterprise, manager);
    }

    @Test void demoReferenceCannotAuthenticateAndDoesNotSurviveLogoutOrAnotherProcess() {
        var login = demo.login("demo", "alice", "demo"); var http = new MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer " + login.token());
        var service = service(properties(false, "EMPLOYEE"), sessions, logouts, demo);
        var reference = service.capture(http, login.actor(), now);
        assertThat(reference.value()).hasSize(64).isNotEqualTo(login.token());
        assertThat(reference.toString()).doesNotContain(reference.value(), login.token());
        assertThat(service.resolve(reference, "demo", "alice", now)).contains(login.actor());
        assertThatThrownBy(() -> demo.authenticate(reference.value())).isInstanceOf(DomainException.class);
        assertThat(service.resolve(reference, "demo", "bob", now)).isEmpty();
        assertThat(service.resolve(reference, "other", "alice", now)).isEmpty();
        assertThat(service(properties(false, "EMPLOYEE"), sessions, logouts, new AuthService(true, "demo"))
                .resolve(reference, "demo", "alice", now)).isEmpty();
        demo.logout(login.token());
        assertThat(service.resolve(reference, "demo", "alice", now)).isEmpty();
    }

    @Test void modeSwitchAndForgedActorCannotReuseDemoLogin() {
        var login = demo.login("demo", "alice", "demo"); var http = new MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer " + login.token());
        var service = service(properties(false, "EMPLOYEE"), sessions, logouts, demo);
        var reference = service.capture(http, login.actor(), now);
        assertThatThrownBy(() -> service.capture(http, new Actor("demo", "alice", Set.of("ADMIN")), now)).isInstanceOf(DomainException.class);
        assertThat(service(enterprise, sessions, logouts, demo).resolve(reference, "demo", "alice", now)).isEmpty();
    }

    @Test void primaryReferenceSurvivesNewRepositoryAndSessionIdRotationWithoutTouchingLastAccess() {
        Session session = login(now.plusSeconds(600)); var http = request(session);
        var service = service(enterprise, sessions, logouts, demo);
        var reference = service.capture(http, actor(), now);
        assertThat(reference.value()).isNotEqualTo(session.getId());
        assertThat(jdbc.queryForObject("SELECT PRIMARY_ID FROM AF_HTTP_SESSION WHERE SESSION_ID=?", String.class, session.getId())).isEqualTo(reference.value());
        var lastAccess = session.getLastAccessedTime(); session.changeSessionId(); save(session);
        assertThat(service(enterprise, repository(), logouts, demo).resolve(reference, "tenant-a", "employee", now)).contains(actor());
        assertThat(jdbc.queryForObject("SELECT LAST_ACCESS_TIME FROM AF_HTTP_SESSION WHERE PRIMARY_ID=?", Long.class, reference.value())).isEqualTo(lastAccess.toEpochMilli());
        assertThat(service.resolve(reference, "tenant-b", "employee", now)).isEmpty();
        assertThat(service.resolve(reference, "tenant-a", "another", now)).isEmpty();
    }

    @Test void changedPolicyDeletedSessionAndDisabledSharedSessionsFailClosed() {
        Session session = login(now.plusSeconds(600));
        var reference = service(enterprise, sessions, logouts, demo).capture(request(session), actor(), now);
        assertThat(service(properties(true, "ADMIN"), sessions, logouts, demo).resolve(reference, "tenant-a", "employee", now)).isEmpty();
        assertThat(service(properties(false, "EMPLOYEE"), sessions, logouts, demo).resolve(reference, "tenant-a", "employee", now)).isEmpty();
        var unavailable = service(enterprise, null, logouts, demo);
        assertThatThrownBy(() -> unavailable.capture(request(session), actor(), now)).isInstanceOf(DomainException.class)
                .extracting(value -> ((DomainException) value).code()).isEqualTo("DEFERRED_AUTHENTICATION_UNAVAILABLE");
        sessions.deleteById(session.getId());
        assertThat(service(enterprise, sessions, logouts, demo).resolve(reference, "tenant-a", "employee", now)).isEmpty();
    }

    @Test void tokenAndSessionExpiryAreBothEnforced() {
        Session session = login(now.plusSeconds(2)); var service = service(enterprise, sessions, logouts, demo);
        var reference = service.capture(request(session), actor(), now);
        assertThat(service.resolve(reference, "tenant-a", "employee", now.plusSeconds(3))).isEmpty();
        session.setLastAccessedTime(now.minusSeconds(600)); session.setMaxInactiveInterval(Duration.ofSeconds(30)); save(session);
        assertThat(service.resolve(reference, "tenant-a", "employee", now)).isEmpty();
    }

    @Test void backchannelRevocationAndMissingLoginOrderInvalidateOriginalAuthorization() {
        Session session = login(now.plusSeconds(600)); var service = service(enterprise, sessions, logouts, demo);
        var reference = service.capture(request(session), actor(), now);
        var logout = Jwt.withTokenValue("verified-test-logout").header("alg", "RS256").subject("employee")
                .jti(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        logouts.revoke(logout);
        assertThat(service.resolve(reference, "tenant-a", "employee", now)).isEmpty();
        Session fresh = login(now.plusSeconds(600));
        var freshReference = service.capture(request(fresh), actor(), now);
        fresh.removeAttribute(OidcLogoutScopes.SESSION_ORDER); save(fresh);
        assertThat(service.resolve(freshReference, "tenant-a", "employee", now)).isEmpty();
    }

    @Test void onlyVerifiedPlatformPrincipalWithMatchingMappedActorIsAccepted() {
        Session session = login(now.plusSeconds(600)); var service = service(enterprise, sessions, logouts, demo);
        var reference = service.capture(request(session), actor(), now);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("employee", "", List.of()))); save(session);
        assertThat(service.resolve(reference, "tenant-a", "employee", now)).isEmpty();
        assertThatThrownBy(() -> service.capture(request(session), actor(), now)).isInstanceOf(DomainException.class);
    }

    private Session login(Instant expires) {
        Session session = sessions.createSession(); session.setLastAccessedTime(now);
        var token = new OidcIdToken("verified-test-id-token", now.minusSeconds(1), expires,
                Map.of("sub", "employee", "iss", enterprise.issuer(), "aud", List.of(enterprise.clientId()), "tenant", "external", "roles", List.of("staff")));
        var principal = new PlatformOidcUser(token, "tenant-a", "employee", Set.of("EMPLOYEE"));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(principal, "", principal.getAuthorities())));
        session.setAttribute(OidcLogoutScopes.SESSION_ORDER, logouts.beginLogin()); save(session); return session;
    }
    @SuppressWarnings("unchecked") private void save(Session session) { ((SessionRepository<Session>) (SessionRepository<?>) sessions).save(session); }
    private JdbcIndexedSessionRepository repository() {
        var repository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(manager)); repository.setTableName("AF_HTTP_SESSION"); return repository;
    }
    private DeferredActorAuthentication service(OidcProperties properties, JdbcIndexedSessionRepository repository, OidcLogoutScopes scopes, AuthService demoService) {
        var beans = new DefaultListableBeanFactory();
        if (repository != null) beans.registerSingleton("sessions", repository);
        if (scopes != null) beans.registerSingleton("logouts", scopes);
        return new DeferredActorAuthentication(demoService, properties, jdbc, beans.getBeanProvider(JdbcIndexedSessionRepository.class), beans.getBeanProvider(OidcLogoutScopes.class));
    }
    private static OidcProperties properties(boolean enabled, String role) {
        return new OidcProperties(enabled, "https://identity.example", "flow", "unused", "tenant", "roles", Map.of("external", "tenant-a"), Map.of("staff", Set.of(role)), false);
    }
    private static Actor actor() { return new Actor("tenant-a", "employee", Set.of("EMPLOYEE")); }
    private static MockHttpServletRequest request(Session session) {
        var request = new MockHttpServletRequest(); request.setSession(new MockHttpSession(null, session.getId())); return request;
    }
}
