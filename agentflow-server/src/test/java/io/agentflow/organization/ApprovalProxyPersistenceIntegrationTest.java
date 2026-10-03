package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * 真实存储验证时间、租户、目录资格、重叠并发、撤销锁和审计回滚。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=false", "agentflow.sla.reminders-enabled=false"})
class ApprovalProxyPersistenceIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_URL", "jdbc:h2:mem:approval-proxy;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_PROXY_TEST_PASSWORD", ""));
    }

    @Autowired OrganizationService organization;
    @Autowired ApprovalProxyService service;
    @Autowired ApprovalProxyRepository proxies;
    @Autowired DefinitionApplicationService definitions;
    @Autowired DefinitionDraftRepository definitionRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OrganizationRepository directory;
    private Actor admin;
    private OrganizationPerson principal;
    private OrganizationPerson substitute;
    private OrganizationPerson alternate;
    private DefinitionDraft definition;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setup() {
        admin = new Actor("proxy-" + UUID.randomUUID(), "administrator", Set.of("ADMIN"));
        organization.initialize(admin);
        principal = organization.createPerson(admin, "issuer:principal/中文", "原审批人", true, true);
        substitute = organization.createPerson(admin, "substitute", "代理人", true, true);
        alternate = organization.createPerson(admin, "alternate", "另一代理人", true, true);
        definition = publish("proxy-" + UUID.randomUUID());
        start = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        end = start.plusSeconds(3600);
    }

    @Test
    void persistsExactScopeTimeBoundsAndAuditWithoutAddingEngineAssignmentsOrRoles() {
        var beforeTasks = jdbc.queryForList("SELECT * FROM ACT_RU_TASK");
        var beforePeople = jdbc.queryForList("SELECT * FROM organization_person WHERE tenant_id=? ORDER BY id", admin.tenantId());
        var value = create(substitute.id(), start, end);
        assertThat(proxies.find(admin.tenantId(), value.id())).contains(value);
        assertThat(active(start.minusNanos(1))).isEmpty();
        assertThat(active(start)).containsExactly(new ApprovalProxyRepository.ActiveProxy(value, principal.subject()));
        assertThat(active(end.minusNanos(1))).hasSize(1);
        assertThat(active(end)).isEmpty();
        assertThat(proxies.activeForSubstitute("other", substitute.subject(), start)).isEmpty();
        assertThat(proxies.find("other", value.id())).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM ACT_RU_TASK")).isEqualTo(beforeTasks);
        assertThat(jdbc.queryForList("SELECT * FROM organization_person WHERE tenant_id=? ORDER BY id", admin.tenantId())).isEqualTo(beforePeople);
        var change = directory.changes(admin.tenantId(), null, 1).get(0);
        assertThat(change.actor()).isEqualTo(admin.userId());
        assertThat(change.kind()).isEqualTo("APPROVAL_PROXY");
        assertThat(change.recordId()).isEqualTo(value.id());
        assertThat(change.snapshotJson()).contains(value.definitionId().toString(), "休假代办");
    }

    @Test
    void disallowsOverlapAcrossDifferentSubstitutesButAllowsAdjacentPeriodsAndOtherVersions() {
        var value = create(substitute.id(), start, end);
        assertCode(() -> create(alternate.id(), start.plusSeconds(1), end.plusSeconds(1)), "APPROVAL_PROXY_OVERLAP");
        assertCode(() -> create(alternate.id(), start.minusSeconds(1), end.plusSeconds(1)), "APPROVAL_PROXY_OVERLAP");
        create(alternate.id(), end, end.plusSeconds(3600));
        var nextVersion = publish(definition.key());
        var otherVersion = service.create(admin, nextVersion.id(), principal.id(), substitute.id(), start, end, "新版本独立授权");
        assertThat(otherVersion.definitionId()).isNotEqualTo(value.definitionId());
        assertThat(proxies.list(admin.tenantId(), principal.id(), "", 10)).hasSize(3);
        assertThat(proxies.list(admin.tenantId(), alternate.id(), "", 10)).hasSize(1);
        var first = proxies.list(admin.tenantId(), null, "", 1);
        assertThat(first).hasSize(2);
        assertThat(proxies.list(admin.tenantId(), null, first.get(0).id().toString(), 10)).hasSize(2);
    }

    @Test
    void requiresAdministratorInitializedDirectoryPublishedTenantScopeAndBothEligiblePeople() {
        assertCode(() -> service.create(new Actor(admin.tenantId(), "ordinary", Set.of("APPROVER")), definition.id(),
                principal.id(), substitute.id(), start, end, "无管理权限"), "FORBIDDEN");
        assertCode(() -> service.create(new Actor("uninitialized", "admin", Set.of("ADMIN")), definition.id(),
                principal.id(), substitute.id(), start, end, "未初始化"), "ORGANIZATION_NOT_INITIALIZED");
        var draft = definitions.create(admin.tenantId(), "draft-" + UUID.randomUUID(), "草稿", definition.graph());
        assertCode(() -> service.create(admin, draft.id(), principal.id(), substitute.id(), start, end, "不能代理草稿"), "APPROVAL_PROXY_DEFINITION_REQUIRED");
        Actor other = new Actor("foreign-" + UUID.randomUUID(), "admin", Set.of("ADMIN"));
        organization.initialize(other);
        var foreignPerson = organization.createPerson(other, "other", "其他租户", true, true);
        assertCode(() -> service.create(other, definition.id(), foreignPerson.id(), substitute.id(), start, end, "跨租户流程"), "APPROVAL_PROXY_DEFINITION_REQUIRED");
        assertCode(() -> create(foreignPerson.id(), start, end), "APPROVAL_PROXY_PERSON_REQUIRED");
        organization.updatePerson(admin, substitute.id(), substitute.displayName(), true, false, 1);
        assertCode(() -> create(substitute.id(), start, end), "APPROVAL_PROXY_PERSON_REQUIRED");
        organization.updatePerson(admin, principal.id(), principal.displayName(), false, true, 1);
        assertCode(() -> create(alternate.id(), start, end), "APPROVAL_PROXY_PERSON_REQUIRED");
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).isEmpty();
    }

    @Test
    void changingEitherParticipantsEligibilityImmediatelyRemovesActiveLookupAndNoProxyChainIsExpanded() {
        create(substitute.id(), start, end);
        service.create(admin, definition.id(), substitute.id(), alternate.id(), start, end, "仅代理其本人任务");
        assertThat(proxies.activeForSubstitute(admin.tenantId(), alternate.subject(), start))
                .extracting(ApprovalProxyRepository.ActiveProxy::principalSubject).containsExactly(substitute.subject());
        organization.updatePerson(admin, substitute.id(), substitute.displayName(), false, true, 1);
        assertThat(active(start)).isEmpty();
        assertThat(proxies.activeForSubstitute(admin.tenantId(), alternate.subject(), start)).isEmpty();
        organization.updatePerson(admin, substitute.id(), substitute.displayName(), true, true, 2);
        assertThat(active(start)).hasSize(1);
        organization.updatePerson(admin, principal.id(), principal.displayName(), true, false, 1);
        assertThat(active(start)).isEmpty();
    }

    @Test
    void disabledStartDoesNotPreventProxyForExistingInstanceOfThatVersion() {
        definition.changeAvailability(definition.revision(), false);
        definitionRepository.save(definition);
        var value = create(substitute.id(), start, end);
        assertThat(value.definitionId()).isEqualTo(definition.id());
        assertThat(active(start)).hasSize(1);
    }

    @Test
    void revocationRetainsOriginalEvidenceRejectsStaleWritesAndAllowsReplacement() {
        var original = create(substitute.id(), start, end);
        var revoked = service.revoke(admin, original.id(), 1, "提前返岗");
        assertThat(proxies.find(admin.tenantId(), original.id())).contains(revoked);
        assertThat(active(start)).isEmpty();
        assertThat(revoked.reason()).isEqualTo(original.reason());
        assertThat(revoked.revocation().reason()).isEqualTo("提前返岗");
        assertCode(() -> service.revoke(admin, original.id(), 1, "旧修订"), "CONCURRENCY_CONFLICT");
        assertCode(() -> service.revoke(admin, original.id(), 2, "重复撤销"), "APPROVAL_PROXY_REVOKED");
        var replacement = create(alternate.id(), start, end);
        assertThat(replacement.id()).isNotEqualTo(original.id());
        assertThat(proxies.list(admin.tenantId(), principal.id(), "", 10)).hasSize(2);
        assertThat(directory.changes(admin.tenantId(), null, 100).stream().filter(change -> change.recordId().equals(original.id())))
                .hasSize(2);
    }

    @Test
    void auditFailureRollsBackCreatedGrantAndDirectoryRevision() {
        long before = directory.revision(admin.tenantId());
        failAudit();
        assertThatThrownBy(() -> create(substitute.id(), start, end)).isInstanceOf(IllegalStateException.class);
        assertThat(proxies.list(admin.tenantId(), null, "", 10)).isEmpty();
        assertThat(directory.revision(admin.tenantId())).isEqualTo(before);
    }

    @Test
    void auditFailureRollsBackRevocationAndLeavesOriginalAuthorityUnchanged() {
        var original = create(substitute.id(), start, end);
        long before = directory.revision(admin.tenantId());
        failAudit();
        assertThatThrownBy(() -> service.revoke(admin, original.id(), 1, "撤销测试")).isInstanceOf(IllegalStateException.class);
        assertThat(proxies.find(admin.tenantId(), original.id())).contains(original);
        assertThat(active(start)).hasSize(1);
        assertThat(directory.revision(admin.tenantId())).isEqualTo(before);
    }

    @Test
    void concurrentOverlappingCreationHasExactlyOneWinnerAndOneAudit() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var futures = List.of(substitute, alternate).stream().map(person -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
                try { create(person.id(), start, end); return "CREATED"; }
                catch (DomainException failure) { return failure.code(); }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "APPROVAL_PROXY_OVERLAP");
            assertThat(proxies.list(admin.tenantId(), null, "", 10)).hasSize(1);
            assertThat(directory.changes(admin.tenantId(), null, 100).stream().filter(change -> change.kind().equals("APPROVAL_PROXY"))).hasSize(1);
        } finally { go.countDown(); executor.shutdownNow(); }
    }

    @Test
    void revocationWaitsForTheSameGrantRowLockThenCommitsOnce() throws Exception {
        var value = create(substitute.id(), start, end);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revokeStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(proxies.lock(admin.tenantId(), value.id())).contains(value);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Row lock was not released"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = executor.submit(() -> { revokeStarted.countDown(); return service.revoke(admin, value.id(), 1, "并发撤销"); });
            assertThat(revokeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> revoke.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(proxies.find(admin.tenantId(), value.id())).contains(value);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(revoke.get(10, TimeUnit.SECONDS).revocation().reason()).isEqualTo("并发撤销");
            assertThat(active(start)).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private DefinitionDraft publish(String key) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, java.util.Map.of()),
                new Node("review", "审批", NodeType.USER_TASK, java.util.Map.of("assigneeRule", "role:ORG_PERSON_" + principal.id())),
                new Node("end", "结束", NodeType.END, java.util.Map.of())),
                List.of(new Edge("e1", "start", "review", ""), new Edge("e2", "review", "end", "")));
        var draft = definitions.create(admin.tenantId(), key, "代理验证流程", graph);
        return definitions.publish(admin, draft.id(), draft.revision(), "代理范围验证");
    }

    private ApprovalProxy create(UUID substituteId, Instant from, Instant to) {
        return service.create(admin, definition.id(), principal.id(), substituteId, from, to, "休假代办");
    }

    private List<ApprovalProxyRepository.ActiveProxy> active(Instant at) {
        return proxies.activeForSubstitute(admin.tenantId(), substitute.subject(), at);
    }

    private void failAudit() {
        doThrow(new IllegalStateException("Audit persistence failed")).when(directory).recordChange(eq(admin.tenantId()),
                anyLong(), any(), eq("APPROVAL_PROXY"), any(), any(), any());
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code);
    }
}
