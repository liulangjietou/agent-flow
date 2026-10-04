package io.agentflow.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 真正 JDBC 事务覆盖来源连续性、映射租户约束、并发领取及业务／游标／轨迹原子性。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncPersistenceTest {
    private static final Instant AT = Instant.parse("2026-10-04T05:00:00Z");
    private static final Actor ADMIN = new Actor("tenant", "admin", Set.of("ADMIN"));
    private static final OrganizationSyncKey PERSON = new OrganizationSyncKey(OrganizationSyncKey.Kind.PERSON, "source-person");
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private JdbcOrganizationSyncRepository sync;
    private JdbcOrganizationRepository organization;
    private OrganizationService service;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:organization-sync-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        Flyway.configure().dataSource(data).target("109").load().migrate(); jdbc = new JdbcTemplate(data);
        var manager = new DataSourceTransactionManager(data); tx = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new JdbcOrganizationSyncRepository(jdbc, json));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); sync = (JdbcOrganizationSyncRepository) proxy.getProxy();
        organization = new JdbcOrganizationRepository(jdbc, json); service = new OrganizationService(organization);
        tx.executeWithoutResult(ignored -> { service.initialize(ADMIN); sync.register(new OrganizationSyncSource("tenant", "hr", 0, 1, null, "admin", AT)); });
    }

    @AfterEach void stopDatabase() { jdbc.execute("SHUTDOWN"); }

    @Test void receiptAndFailedOrCancelledReadsLeaveOrganizationAndCursorUnchanged() {
        var before = jdbc.queryForList("SELECT * FROM organization_directory"); var batch = received(0, 1);
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isZero();
        assertThat(sync.due(AT.plusSeconds(60))).isEmpty();
        var restored = new JdbcOrganizationSyncRepository(jdbc, json).find("tenant", batch.context().id()).orElseThrow();
        assertThat(restored.context()).isEqualTo(batch.context()); assertThat(restored.state()).isEqualTo(batch.state());
        tx.executeWithoutResult(ignored -> { batch.cancel(3, "reviewer", "稍后重新读取", AT.plusSeconds(4)); sync.update(batch, 3); });
        assertThat(versions(batch)).containsExactly(1L, 2L, 3L, 4L);
        var next = fresh("tenant", "hr", 0, batch.context().id()); tx.executeWithoutResult(ignored -> sync.create(next));
        assertThat(sync.page("tenant", 0, 1).total()).isEqualTo(2); assertThat(sync.page("tenant", 1, 1).items()).hasSize(1);
        assertThat(json.write(sync.page("tenant", 0, 20))).doesNotContain("source-person", "displayName", "appointments", "targetDigest");
        assertThat(sync.find("foreign", batch.context().id())).isEmpty(); assertThat(sync.source("foreign")).isEmpty();
        assertThat(sync.page("foreign", 0, 20).items()).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM organization_directory")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization_person", Long.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"source", "cursor", "tenant", "nonterminal-retry"})
    void queueRequiresRegisteredSourceCurrentCursorAndTerminalRetry(String variant) {
        UUID retry = null;
        if (variant.equals("nonterminal-retry")) { var old = received(0, 1); retry = old.context().id(); }
        var batch = fresh(variant.equals("tenant") ? "foreign" : "tenant", variant.equals("source") ? "other" : "hr", variant.equals("cursor") ? 1 : 0, retry);
        long before = count("organization_sync_batch");
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.create(batch)), "CONCURRENCY_CONFLICT");
        assertThat(count("organization_sync_batch")).isEqualTo(before);
    }

    @Test void competingQueueAndClaimHaveOneWinnerWithStableOriginalLease() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1); var one = fresh("tenant", "hr", 0, null); var two = fresh("tenant", "hr", 0, null);
            var futures = List.of(one, two).stream().map(batch -> pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { tx.executeWithoutResult(ignored -> sync.create(batch)); return true; }
                catch (DomainException conflict) { assertThat(conflict.code()).isEqualTo("ORGANIZATION_SYNC_BATCH_ACTIVE"); return false; }
            })).toList(); start.countDown();
            int successes = 0; for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1); assertThat(count("organization_sync_transition")).isEqualTo(1);
            UUID id = sync.page("tenant", 0, 20).items().get(0).id(); var claim = new CountDownLatch(1);
            var claims = List.of(1, 2).stream().map(unused -> pool.submit(() -> {
                claim.await(5, TimeUnit.SECONDS);
                return tx.execute(ignored -> {
                    organization.lock("tenant"); sync.lockSource("tenant"); sync.lockBatch("tenant", id);
                    var batch = sync.find("tenant", id).orElseThrow(); if (batch.state().status() != OrganizationSyncBatch.Status.QUEUED) return false;
                    batch.start(1, AT.plusSeconds(1), AT.plusSeconds(30)); sync.update(batch, 1); return true;
                });
            })).toList(); claim.countDown(); successes = 0; for (var future : claims) if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) successes++;
            assertThat(successes).isEqualTo(1); var restored = sync.find("tenant", id).orElseThrow();
            assertThat(restored.state().leaseUntil()).isEqualTo(AT.plusSeconds(30)); assertThat(versions(restored)).containsExactly(1L, 2L);
            assertThat(sync.due(AT.plusSeconds(29))).isEmpty(); assertThat(sync.due(AT.plusSeconds(30))).extracting(JdbcOrganizationSyncRepository.Candidate::id).containsExactly(id);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void writesRequireTransactionAndRegistrationRequiresExistingLocalDirectory() {
        assertThatThrownBy(() -> sync.create(fresh("tenant", "hr", 0, null))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> sync.register(new OrganizationSyncSource("absent", "hr", 0, 1, null, "admin", AT))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.register(new OrganizationSyncSource("tenant", "other", 0, 1, null, "admin", AT))), "ORGANIZATION_SYNC_SOURCE_CONFLICT");
    }

    @ParameterizedTest @ValueSource(strings = {"create", "claim"})
    void transitionFailureRollsBackTheWholeStateWrite(String stage) {
        var batch = fresh("tenant", "hr", 0, null);
        if (stage.equals("claim")) tx.executeWithoutResult(ignored -> sync.create(batch));
        jdbc.execute("ALTER TABLE organization_sync_transition ADD CONSTRAINT force_failure CHECK(batch_version<>" + (stage.equals("create") ? 1 : 2) + ")");
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
            if (stage.equals("create")) sync.create(batch); else { batch.start(1, AT, AT.plusSeconds(30)); sync.update(batch, 1); }
        })).isInstanceOf(DataIntegrityViolationException.class);
        if (stage.equals("create")) assertThat(sync.find("tenant", batch.context().id())).isEmpty();
        else assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state().status()).isEqualTo(OrganizationSyncBatch.Status.QUEUED);
    }

    @Test void organizationApplicationBindingAndCursorCommitTogetherAndRestoreHistory() {
        var person = tx.execute(ignored -> service.createPerson(ADMIN, "subject", "原姓名", true, true)); var batch = received(0, 1);
        tx.executeWithoutResult(ignored -> applyPerson(batch, person));
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(1);
        var binding = sync.binding("tenant", PERSON).orElseThrow(); assertThat(binding.localId()).isEqualTo(person.id()); assertThat(binding.localRevision()).isEqualTo(2);
        assertThat(binding.sourceRevision()).isEqualTo(1); assertThat(count("organization_sync_binding_change")).isEqualTo(1);
        assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state().status()).isEqualTo(OrganizationSyncBatch.Status.APPLIED);
        assertThat(new JdbcOrganizationSyncRepository(jdbc, json).binding("tenant", PERSON)).contains(binding);
        assertThat(sync.binding("foreign", PERSON)).isEmpty();
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.advance(sync.source("tenant").orElseThrow(), 1)), "CONCURRENCY_CONFLICT");
    }

    @Test void laterMappingHistoryFailureRollsBackOrganizationAuditBatchBindingAndCursor() {
        var person = tx.execute(ignored -> service.createPerson(ADMIN, "subject", "原姓名", true, true)); var batch = received(0, 1);
        var tables = List.of("organization_person", "organization_directory", "organization_change", "organization_sync_source", "organization_sync_batch", "organization_sync_transition");
        var before = tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList();
        jdbc.execute("ALTER TABLE organization_sync_binding_change ADD CONSTRAINT reject_binding CHECK(binding_version<>1)");
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> applyPerson(batch, person))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tables.stream().map(table -> jdbc.queryForList("SELECT * FROM " + table)).toList()).isEqualTo(before);
        assertThat(count("organization_sync_binding")).isZero(); assertThat(count("organization_sync_binding_change")).isZero();
    }

    @Test void staleLocalRevisionCannotBecomeTheNewMappingBaseline() {
        var person = tx.execute(ignored -> service.createPerson(ADMIN, "subject", "原姓名", true, true)); var batch = received(0, 1);
        tx.executeWithoutResult(ignored -> {
            batch.apply(3, 0, new OrganizationSyncBatch.Applied("a".repeat(64), 2, 2), "admin", null, AT.plusSeconds(3)); sync.update(batch, 3);
            service.updatePerson(ADMIN, person.id(), "管理员已改", true, true, 1);
        });
        var stale = new OrganizationSyncBinding("tenant", "hr", PERSON, person.id(), 1, 1, 1, batch.context().id(), AT.plusSeconds(3));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.save(stale, 0)), "CONCURRENCY_CONFLICT");
        assertThat(sync.binding("tenant", PERSON)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"after-revision", "actor", "created-at", "received-revision", "source-key", "lease"})
    void restorationRejectsChangedQueryIndexesOrBindingContext(String variant) {
        var batch = received(0, 2); var id = batch.context().id().toString();
        switch (variant) {
            case "after-revision" -> jdbc.update("UPDATE organization_sync_batch SET after_revision=1 WHERE id=?", id);
            case "actor" -> jdbc.update("UPDATE organization_sync_batch SET requested_by='other' WHERE id=?", id);
            case "created-at" -> jdbc.update("UPDATE organization_sync_batch SET created_at=? WHERE id=?", java.sql.Timestamp.from(AT.minusSeconds(1)), id);
            case "received-revision" -> jdbc.update("UPDATE organization_sync_batch SET received_revision=3 WHERE id=?", id);
            case "source-key" -> {
                var old = batch.context(); var forged = new OrganizationSyncBatch.Context(old.id(), old.tenantId(), "other", old.afterRevision(), old.targetDigest(), old.requestedBy(), old.createdAt(), null);
                jdbc.update("UPDATE organization_sync_batch SET context_json=? WHERE id=?", json.write(forged), id);
            }
            case "lease" -> {
                var old = batch.state(); var forged = new OrganizationSyncBatch.State(old.status(), old.version(), old.startedAt(), AT, old.receivedAt(), old.delta(), old.failure(), old.finishedAt(), old.decision());
                jdbc.update("UPDATE organization_sync_batch SET state_json=? WHERE id=?", json.write(forged), id);
            }
            default -> throw new AssertionError(variant);
        }
        assertThatThrownBy(() -> sync.find("tenant", batch.context().id())).isInstanceOfAny(IllegalStateException.class, DomainException.class);
    }

    @Test void cannotBindAnotherTenantsEntityOrTheWrongUnitKind() {
        var other = new Actor("other", "admin", Set.of("ADMIN"));
        var foreign = tx.execute(ignored -> { service.initialize(other); return service.createPerson(other, "subject", "他租户", true, true); });
        var unit = tx.execute(ignored -> service.createUnit(ADMIN, OrganizationUnit.Kind.LEGAL_ENTITY, "法人", null, null, true)); var batch = received(0, 1);
        tx.executeWithoutResult(ignored -> { batch.apply(3, 0, new OrganizationSyncBatch.Applied("a".repeat(64), 2, 2), "admin", null, AT.plusSeconds(3)); sync.update(batch, 3); });
        var foreignBinding = new OrganizationSyncBinding("tenant", "hr", PERSON, foreign.id(), 1, 1, 1, batch.context().id(), AT.plusSeconds(3));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.save(foreignBinding, 0)), "CONCURRENCY_CONFLICT");
        var wrongKind = new OrganizationSyncBinding("tenant", "hr", new OrganizationSyncKey(OrganizationSyncKey.Kind.DEPARTMENT, "d"), unit.id(), 1, 1, 1, batch.context().id(), AT.plusSeconds(3));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.save(wrongKind, 0)), "CONCURRENCY_CONFLICT");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO organization_sync_binding(tenant_id,source_key,kind,external_id,local_id,person_id,local_revision,source_revision,version,applied_batch_id,updated_at)
                VALUES('tenant','hr','PERSON','foreign',?,?,1,1,1,?,CURRENT_TIMESTAMP)
                """, foreign.id().toString(), foreign.id().toString(), batch.context().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void advancesBindingWithoutRebindingAndKeepsEveryAppliedSnapshot() {
        var person = tx.execute(ignored -> service.createPerson(ADMIN, "subject", "原姓名", true, true));
        var first = received(0, 1); tx.executeWithoutResult(ignored -> applyPerson(first, person));
        var original = sync.binding("tenant", PERSON).orElseThrow(); var second = received(1, 2);
        tx.executeWithoutResult(ignored -> {
            var source = sync.source("tenant").orElseThrow();
            var updated = service.updatePerson(ADMIN, person.id(), "再次同步", false, false, 2);
            second.apply(3, 1, new OrganizationSyncBatch.Applied("b".repeat(64), 3, 4), "reviewer", "明确停用", AT.plusSeconds(4)); sync.update(second, 3);
            sync.save(original.applied(1, updated.revision(), 2, second.context().id(), AT.plusSeconds(4)), 1);
            sync.advance(source.applied(second, source.version()), source.version());
        });
        var latest = sync.binding("tenant", PERSON).orElseThrow(); assertThat(latest.version()).isEqualTo(2); assertThat(latest.localId()).isEqualTo(person.id());
        assertThat(latest.localRevision()).isEqualTo(3); assertThat(organization.person("tenant", person.id()).orElseThrow().subject()).isEqualTo("subject");
        var history = jdbc.queryForList("SELECT snapshot_json FROM organization_sync_binding_change ORDER BY binding_version", String.class);
        assertThat(history).hasSize(2); assertThat(json.read(history.get(0), OrganizationSyncBinding.class)).isEqualTo(original);
        assertThat(json.read(history.get(1), OrganizationSyncBinding.class)).isEqualTo(latest);
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(2);
        var other = tx.execute(ignored -> service.createPerson(ADMIN, "another", "另一人", false, false));
        var replacement = new OrganizationSyncBinding("tenant", "hr", PERSON, other.id(), other.revision(), 2, 2, second.context().id(), AT.plusSeconds(4));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.save(replacement, 1)), "CONCURRENCY_CONFLICT");
        assertThat(sync.binding("tenant", PERSON)).contains(latest);
        var alias = new OrganizationSyncBinding("tenant", "hr", new OrganizationSyncKey(OrganizationSyncKey.Kind.PERSON, "alias"), person.id(), 3, 2, 1, second.context().id(), AT.plusSeconds(4));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.save(alias, 0)), "ORGANIZATION_SYNC_BINDING_CONFLICT");
        assertThat(count("organization_sync_binding_change")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-history", "changed-index"})
    void bindingRestorationRequiresTheOriginalLatestAudit(String variant) {
        var person = tx.execute(ignored -> service.createPerson(ADMIN, "subject", "原姓名", true, true));
        var batch = received(0, 1); tx.executeWithoutResult(ignored -> applyPerson(batch, person));
        if (variant.equals("missing-history")) jdbc.update("DELETE FROM organization_sync_binding_change");
        else jdbc.update("UPDATE organization_sync_binding SET local_revision=3");
        assertThatThrownBy(() -> sync.binding("tenant", PERSON)).isInstanceOf(IllegalStateException.class);
    }

    @Test void cancelledReadCannotBeOverwrittenByALateReceivedResponse() {
        var batch = fresh("tenant", "hr", 0, null); tx.executeWithoutResult(ignored -> sync.create(batch));
        tx.executeWithoutResult(ignored -> { batch.start(1, AT, AT.plusSeconds(30)); sync.update(batch, 1); });
        var late = OrganizationSyncBatch.restore(batch.context(), batch.state());
        tx.executeWithoutResult(ignored -> { batch.cancel(2, "reviewer", "取消读取", AT.plusSeconds(1)); sync.update(batch, 2); });
        late.receive(2, new OrganizationSyncDelta("hr", 0, 1, List.of(), List.of(), List.of()), AT.plusSeconds(2));
        assertCode(() -> tx.executeWithoutResult(ignored -> sync.update(late, 2)), "CONCURRENCY_CONFLICT");
        assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state()).isEqualTo(batch.state());
    }

    private void applyPerson(OrganizationSyncBatch batch, OrganizationPerson before) {
        organization.lock("tenant"); sync.lockSource("tenant"); sync.lockBatch("tenant", batch.context().id());
        long revision = organization.revision("tenant"); var source = sync.source("tenant").orElseThrow();
        var person = service.updatePerson(ADMIN, before.id(), "同步姓名", true, true, before.revision());
        batch.apply(3, source.appliedRevision(), new OrganizationSyncBatch.Applied("a".repeat(64), revision, organization.revision("tenant")), "admin", "核对后应用", AT.plusSeconds(3)); sync.update(batch, 3);
        sync.advance(source.applied(batch, source.version()), source.version());
        sync.save(new OrganizationSyncBinding("tenant", "hr", PERSON, person.id(), person.revision(), batch.state().delta().revision(), 1, batch.context().id(), AT.plusSeconds(3)), 0);
    }
    private OrganizationSyncBatch received(long from, long to) {
        var batch = fresh("tenant", "hr", from, null); tx.executeWithoutResult(ignored -> sync.create(batch));
        tx.executeWithoutResult(ignored -> { batch.start(1, AT.plusSeconds(1), AT.plusSeconds(30)); sync.update(batch, 1); });
        tx.executeWithoutResult(ignored -> { batch.receive(2, new OrganizationSyncDelta("hr", from, to, List.of(), List.of(new OrganizationSyncDelta.Person(PERSON, "subject", "同步姓名", true, true)), List.of()), AT.plusSeconds(2)); sync.update(batch, 2); });
        return batch;
    }
    private static OrganizationSyncBatch fresh(String tenant, String source, long after, UUID retry) { return new OrganizationSyncBatch(new OrganizationSyncBatch.Context(UUID.randomUUID(), tenant, source, after, "a".repeat(64), "admin", AT, retry)); }
    private List<Long> versions(OrganizationSyncBatch batch) { return jdbc.queryForList("SELECT batch_version FROM organization_sync_transition WHERE tenant_id=? AND batch_id=? ORDER BY batch_version", Long.class, batch.context().tenantId(), batch.context().id().toString()); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static void assertCode(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code); }
}
