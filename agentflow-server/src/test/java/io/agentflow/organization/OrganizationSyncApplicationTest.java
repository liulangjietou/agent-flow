package io.agentflow.organization;
import static io.agentflow.organization.OrganizationSyncKey.Kind.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;

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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真正组织用例、持久计划及同事务应用覆盖冲突、乱序关系、并发和失败回滚。
 *
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncApplicationTest {
    private static final Actor ADMIN = new Actor("tenant", "admin", Set.of("ADMIN"));
    private final JsonUtil json = new JsonUtil(new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcOrganizationRepository organization;
    private JdbcOrganizationSyncRepository sync;
    private JdbcOrganizationSyncPlanRepository plans;
    private OrganizationService local;
    private OrganizationSyncApplicationService service;
    private OrganizationSyncConfiguration configuration;

    @BeforeEach void setup() {
        var data = new DriverManagerDataSource("jdbc:h2:mem:sync-application-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        Flyway.configure().dataSource(data).load().migrate(); jdbc = new JdbcTemplate(data);
        var manager = new DataSourceTransactionManager(data); tx = new TransactionTemplate(manager);
        organization = new JdbcOrganizationRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.organization.mapper.OrganizationRepositoryMapper
                                        .class), json); local = new OrganizationService(organization);
        sync = new JdbcOrganizationSyncRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.organization.mapper.OrganizationSyncRepositoryMapper
                                        .class), json); plans = new JdbcOrganizationSyncPlanRepository(
                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                (jdbc).getDataSource(),
                                io.agentflow.organization.mapper
                                        .OrganizationSyncPlanRepositoryMapper.class), json);
        configuration = new OrganizationSyncConfiguration(); configuration.setEnabled(true);
        var target = new OrganizationSyncConfiguration.Target(); target.setSourceKey("hr"); target.setEndpoint("https://organization.invalid/"); target.setToken("synthetic-token");
        configuration.setTenants(java.util.Map.of("tenant", target)); configuration.validate();
        var proxy = new ProxyFactory(new OrganizationSyncApplicationService(organization, sync, plans, new OrganizationSyncPlanner(organization, sync), configuration));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); service = (OrganizationSyncApplicationService) proxy.getProxy();
        tx.executeWithoutResult(ignored -> { local.initialize(ADMIN); sync.register(new OrganizationSyncSource("tenant", "hr", 0, 1, null, "admin", now())); });
    }
    @AfterEach void stopDatabase() { jdbc.execute("SHUTDOWN"); }

    @Test void previewsWithoutOrganizationWritesAndAppliesOutOfOrderFinalRelationships() {
        var batch = received(full()); var saved = preview(batch);
        assertThat(saved.plan().ready()).isTrue(); assertThat(saved.plan().changedRecords()).isEqualTo(8);
        assertThat(count("organization_unit")).isZero(); assertThat(count("organization_person")).isZero();
        assertThat(organization.revision("tenant")).isEqualTo(1); assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isZero();
        assertThat(new JdbcOrganizationSyncPlanRepository(
                                        io.agentflow.mybatis.MyBatisTestSupport.mapper(
                                                (jdbc).getDataSource(),
                                                io.agentflow.organization.mapper
                                                        .OrganizationSyncPlanRepositoryMapper
                                                        .class), json).find("tenant", saved.plan().id()).orElseThrow()).isEqualTo(saved);
        var state = service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), "已逐项核对");
        assertThat(state.status()).isEqualTo(OrganizationSyncBatch.Status.APPLIED); assertThat(organization.revision("tenant")).isEqualTo(9);
        assertThat(count("organization_change")).isEqualTo(8); assertThat(count("organization_sync_binding")).isEqualTo(8);
        var child = organization.unit("tenant", mapped(DEPARTMENT, "child")).orElseThrow();
        assertThat(child.headAppointmentId()).isEqualTo(mapped(APPOINTMENT, "employee-job")); assertThat(child.revision()).isEqualTo(2);
        var job = organization.appointment("tenant", mapped(APPOINTMENT, "employee-job")).orElseThrow();
        assertThat(job.supervisorAppointmentId()).isEqualTo(mapped(APPOINTMENT, "boss-job")); assertThat(job.revision()).isEqualTo(2);
        assertThat(plans.appliedPlan("tenant", batch.context().id()).orElseThrow()).isEqualTo(saved);
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(1);
    }

    @Test void existingSubjectRequiresExplicitAdoptionAndHistoryRetainsTheReviewedBeforeValue() {
        var existing = tx.execute(ignored -> local.createPerson(ADMIN, "subject", "本地姓名", true, false));
        var batch = received(personDelta(0, 1, "source-person", "subject", "来源姓名")); var blocked = preview(batch);
        assertThat(blocked.plan().conflicts()).extracting(OrganizationSyncPlan.Conflict::code).containsExactly("ORGANIZATION_SYNC_ADOPTION_REQUIRED");
        assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, blocked.plan().id(), null), "ORGANIZATION_SYNC_PLAN_CONFLICT");
        var chosen = service.preflight(ADMIN, batch.context().id(), 3, List.of(selection("source-person", existing)));
        assertThat(chosen.plan().ready()).isTrue(); assertThat(chosen.plan().people().get(0).before()).isEqualTo(existing);
        service.apply(ADMIN, batch.context().id(), 3, chosen.plan().id(), "采用既有人员");
        assertThat(mapped(PERSON, "source-person")).isEqualTo(existing.id());
        assertThat(organization.person("tenant", existing.id()).orElseThrow().displayName()).isEqualTo("来源姓名");
        assertThat(plans.appliedPlan("tenant", batch.context().id()).orElseThrow().plan().people().get(0).before().displayName()).isEqualTo("本地姓名");
        assertThat(count("organization_person")).isEqualTo(1);
    }

    @Test void localDriftRequiresANewExplicitVersionAndPreviouslyReadyPlanCannotOverwriteIt() {
        apply(received(personDelta(0, 1, "p", "subject", "来源一")));
        var batch = received(personDelta(1, 2, "p", "subject", "来源二")); var old = preview(batch); UUID id = mapped(PERSON, "p");
        var before = organization.person("tenant", id).orElseThrow();
        var edited = tx.execute(ignored -> local.updatePerson(ADMIN, id, "人工修改", true, false, before.revision()));
        assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, old.plan().id(), null), "ORGANIZATION_SYNC_PLAN_STALE");
        assertThat(preview(batch).plan().conflicts()).extracting(OrganizationSyncPlan.Conflict::code).containsExactly("ORGANIZATION_SYNC_LOCAL_CHANGED");
        var stale = service.preflight(ADMIN, batch.context().id(), 3, List.of(selection("p", before)));
        assertThat(stale.plan().conflicts()).extracting(OrganizationSyncPlan.Conflict::code).containsExactly("ORGANIZATION_SYNC_SELECTION_STALE");
        var chosen = service.preflight(ADMIN, batch.context().id(), 3, List.of(selection("p", edited))); assertThat(chosen.plan().ready()).isTrue();
        service.apply(ADMIN, batch.context().id(), 3, chosen.plan().id(), "确认采用来源修改");
        assertThat(organization.person("tenant", id).orElseThrow().displayName()).isEqualTo("来源二");
        assertThat(sync.binding("tenant", key(PERSON, "p")).orElseThrow().version()).isEqualTo(2);
        assertThat(count("organization_sync_binding_change")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"subject", "legal", "appointment-person", "appointment-position", "rebind"})
    void rejectsReplacementOfStableIdentityEvenWithAnExplicitSelection(String variant) {
        apply(received(full())); var units = new ArrayList<OrganizationSyncDelta.Unit>(); var people = new ArrayList<OrganizationSyncDelta.Person>();
        var jobs = new ArrayList<OrganizationSyncDelta.Appointment>(); var selections = new ArrayList<OrganizationSyncPlan.Selection>();
        switch (variant) {
            case "subject" -> people.add(person("employee", "another-subject", "改绑", true));
            case "legal" -> {
                units.add(unit("another-legal", LEGAL_ENTITY, null, null, null));
                units.add(unit("child", DEPARTMENT, "another-legal", null, null));
            }
            case "appointment-person" -> jobs.add(job("employee-job", "boss", "child", "position", null));
            case "appointment-position" -> {
                units.add(unit("another-position", POSITION, "legal", null, null));
                jobs.add(job("employee-job", "employee", "child", "another-position", null));
            }
            case "rebind" -> {
                people.add(person("employee", "employee-subject", "员工", true));
                selections.add(new OrganizationSyncPlan.Selection(key(PERSON, "employee"), mapped(PERSON, "boss"), 1));
            }
            default -> throw new AssertionError(variant);
        }
        var batch = received(new OrganizationSyncDelta("hr", 1, 2, units, people, jobs));
        var plan = service.preflight(ADMIN, batch.context().id(), 3, selections).plan();
        assertThat(plan.ready()).isFalse(); assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, plan.id(), null), "ORGANIZATION_SYNC_PLAN_CONFLICT");
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(1); assertThat(count("organization_sync_application")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"department-cycle", "supervisor-cycle", "same-person-chain", "inactive-head", "cross-legal", "missing", "duplicate-subject", "duplicate-appointment"})
    void preflightChecksTheFinalGraphAndRejectsInvalidBatchesWithoutPartialWrites(String variant) {
        var facts = full(); var units = new ArrayList<>(facts.units()); var people = new ArrayList<>(facts.people()); var jobs = new ArrayList<>(facts.appointments());
        switch (variant) {
            case "department-cycle" -> units.set(3, unit("root", DEPARTMENT, "legal", "child", "boss-job"));
            case "supervisor-cycle" -> jobs.set(1, job("boss-job", "boss", "root", "position", "employee-job"));
            case "same-person-chain" -> jobs.set(1, job("boss-job", "employee", "root", "position", null));
            case "inactive-head" -> people.set(1, person("boss", "boss-subject", "主管", false));
            case "cross-legal" -> { units.add(unit("legal-two", LEGAL_ENTITY, null, null, null)); units.set(1, unit("position", POSITION, "legal-two", null, null)); }
            case "missing" -> jobs.set(1, job("boss-job", "boss", "root", "missing-position", null));
            case "duplicate-subject" -> people.set(1, person("boss", "employee-subject", "冲突人员", true));
            case "duplicate-appointment" -> jobs.set(1, job("boss-job", "employee", "child", "position", null));
            default -> throw new AssertionError(variant);
        }
        var batch = received(new OrganizationSyncDelta("hr", 0, 1, units, people, jobs)); var saved = preview(batch);
        assertThat(saved.plan().ready()).isFalse(); assertThat(saved.plan().conflicts()).isNotEmpty();
        assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), null), "ORGANIZATION_SYNC_PLAN_CONFLICT");
        assertThat(count("organization_unit")).isZero(); assertThat(count("organization_person")).isZero();
        assertThat(organization.revision("tenant")).isEqualTo(1); assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"organization_change", "organization_sync_binding_change", "organization_sync_application", "organization_sync_source"})
    void anyLateWriteFailureRollsBackFactsHistoryBatchMappingsAndCursor(String table) {
        var batch = received(full()); var saved = preview(batch);
        String constraint = switch (table) {
            case "organization_change" -> "revision<0";
            case "organization_sync_binding_change" -> "binding_version<0";
            case "organization_sync_application" -> "plan_id='blocked'";
            case "organization_sync_source" -> "version=1";
            default -> throw new AssertionError(table);
        };
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT refuse_sync CHECK(" + constraint + ")");
        assertThatThrownBy(() -> service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("organization_unit")).isZero(); assertThat(count("organization_person")).isZero();
        for (String name : List.of("organization_change", "organization_sync_binding", "organization_sync_binding_change", "organization_sync_application")) assertThat(count(name)).isZero();
        assertThat(organization.revision("tenant")).isEqualTo(1); assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isZero();
        assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state().status()).isEqualTo(OrganizationSyncBatch.Status.RECEIVED);
        assertThat(count("organization_sync_plan")).isEqualTo(1);
        jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT refuse_sync");
        service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), "重试原计划"); assertThat(count("organization_sync_application")).isEqualTo(1);
    }

    @Test void concurrentApplicationCommitsOneNamedDecisionAndOneSetOfFacts() throws Exception {
        var batch = received(full()); var saved = preview(batch); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var attempts = List.of("reviewer-one", "reviewer-two").stream().map(name -> pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try { service.apply(new Actor("tenant", name, Set.of("ADMIN")), batch.context().id(), 3, saved.plan().id(), "核对完成"); return true; }
                catch (DomainException rejected) { assertThat(rejected.code()).isEqualTo("ORGANIZATION_SYNC_PLAN_STALE"); return false; }
            })).toList(); start.countDown(); int successes = 0;
            for (var attempt : attempts) if (attempt.get(15, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1); assertThat(count("organization_change")).isEqualTo(8); assertThat(count("organization_sync_application")).isEqualTo(1);
            assertThat(sync.find("tenant", batch.context().id()).orElseThrow().state().decision().actor()).isIn("reviewer-one", "reviewer-two");
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void unchangedAdoptionAndEmptySourceRevisionsDoNotManufactureOrganizationEdits() {
        var existing = tx.execute(ignored -> local.createPerson(ADMIN, "subject", "姓名", true, true)); long revision = organization.revision("tenant");
        var batch = received(personDelta(0, 1, "p", "subject", "姓名"));
        var saved = service.preflight(ADMIN, batch.context().id(), 3, List.of(selection("p", existing))); assertThat(saved.plan().changedRecords()).isZero();
        service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), null);
        apply(received(new OrganizationSyncDelta("hr", 1, 2, List.of(), List.of(), List.of())));
        apply(received(new OrganizationSyncDelta("hr", 2, 2, List.of(), List.of(), List.of())));
        assertThat(organization.revision("tenant")).isEqualTo(revision); assertThat(count("organization_change")).isEqualTo(1);
        assertThat(sync.source("tenant").orElseThrow().version()).isEqualTo(4); assertThat(count("organization_sync_binding_change")).isEqualTo(1);
    }

    @Test void batchDeactivationRetainsExistingHeadAndSupervisorHistoryLikeManualDeactivation() {
        var original = received(full()); apply(original); var facts = full();
        UUID childId = mapped(DEPARTMENT, "child"), employeeJobId = mapped(APPOINTMENT, "employee-job"), bossJobId = mapped(APPOINTMENT, "boss-job");
        var delta = new OrganizationSyncDelta("hr", 1, 2,
                facts.units().stream().map(value -> new OrganizationSyncDelta.Unit(value.key(), value.name(), value.legalEntity(), value.parentDepartment(), false, value.headAppointment())).toList(),
                facts.people().stream().map(value -> new OrganizationSyncDelta.Person(value.key(), value.subject(), value.displayName(), false, value.approvalEligible())).toList(),
                facts.appointments().stream().map(value -> new OrganizationSyncDelta.Appointment(value.key(), value.person(), value.department(), value.position(), false, value.supervisorAppointment())).toList());
        var batch = received(delta); var saved = preview(batch);
        assertThat(saved.plan().conflicts()).isEmpty();
        service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), "停用并保留历史关系");
        var child = organization.unit("tenant", childId).orElseThrow(); assertThat(child.active()).isFalse(); assertThat(child.headAppointmentId()).isEqualTo(employeeJobId);
        var employeeJob = organization.appointment("tenant", employeeJobId).orElseThrow();
        assertThat(employeeJob.active()).isFalse(); assertThat(employeeJob.supervisorAppointmentId()).isEqualTo(bossJobId);
        assertThat(organization.person("tenant", mapped(PERSON, "boss")).orElseThrow().canApprove()).isFalse();
        assertThat(plans.appliedPlan("tenant", original.context().id()).orElseThrow().plan().appointments().get(0).after().active()).isTrue();
        assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(2);
    }

    @Test void authorizationTenantScopeAndCancellationAreCheckedBeforeApplyingAPlan() {
        var batch = received(full()); var saved = preview(batch); var employee = new Actor("tenant", "employee", Set.of("EMPLOYEE"));
        assertCode(() -> service.preflight(employee, batch.context().id(), 3, List.of()), "FORBIDDEN");
        assertCode(() -> service.plan(employee, saved.plan().id()), "FORBIDDEN");
        assertCode(() -> service.apply(employee, batch.context().id(), 3, saved.plan().id(), null), "FORBIDDEN");
        assertCode(() -> service.plan(new Actor("foreign", "admin", Set.of("ADMIN")), saved.plan().id()), "NOT_FOUND");
        tx.executeWithoutResult(ignored -> { batch.cancel(3, "admin", "取消", now()); sync.update(batch, 3); });
        assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), null), "ORGANIZATION_SYNC_PLAN_STALE");
        assertThat(count("organization_unit")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "already-bound", "duplicate-local"})
    void adoptionCannotBorrowAnotherTenantOrAliasAnExistingBinding(String variant) {
        apply(received(personDelta(0, 1, "p", "original-subject", "原人员")));
        var facts = new ArrayList<OrganizationSyncDelta.Person>(); var selections = new ArrayList<OrganizationSyncPlan.Selection>();
        String expected;
        if (variant.equals("foreign")) {
            var foreign = new Actor("foreign", "admin", Set.of("ADMIN"));
            var person = tx.execute(ignored -> { local.initialize(foreign); return local.createPerson(foreign, "foreign-subject", "外租户人员", true, true); });
            facts.add(person("new", "foreign-subject", "人员", true)); selections.add(selection("new", person)); expected = "ORGANIZATION_SYNC_REFERENCE_MISSING";
        } else if (variant.equals("already-bound")) {
            var person = organization.person("tenant", mapped(PERSON, "p")).orElseThrow();
            facts.add(person("new", person.subject(), "人员", true)); selections.add(selection("new", person)); expected = "ORGANIZATION_SYNC_ALREADY_BOUND";
        } else {
            var person = tx.execute(ignored -> local.createPerson(ADMIN, "local-subject", "本地未绑定人员", true, true));
            for (String key : List.of("new-one", "new-two")) { facts.add(person(key, person.subject(), "人员", true)); selections.add(selection(key, person)); }
            expected = "ORGANIZATION_SYNC_DUPLICATE_LOCAL";
        }
        long before = organization.revision("tenant"); var batch = received(new OrganizationSyncDelta("hr", 1, 2, List.of(), facts, List.of()));
        var saved = service.preflight(ADMIN, batch.context().id(), 3, selections);
        assertThat(saved.plan().conflicts()).extracting(OrganizationSyncPlan.Conflict::code).contains(expected);
        assertCode(() -> service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), null), "ORGANIZATION_SYNC_PLAN_CONFLICT");
        assertThat(organization.revision("tenant")).isEqualTo(before); assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"unknown-key", "duplicate-key"})
    void choicesMustUniquelyBelongToTheRequestedDelta(String variant) {
        var person = tx.execute(ignored -> local.createPerson(ADMIN, "subject", "人员", true, true));
        var batch = received(personDelta(0, 1, "p", "subject", "来源"));
        var choices = variant.equals("unknown-key") ? List.of(selection("other", person)) : List.of(selection("p", person), selection("p", person));
        assertCode(() -> service.preflight(ADMIN, batch.context().id(), 3, choices), "INVALID_ORGANIZATION_SYNC_SELECTION");
        assertThat(count("organization_sync_plan")).isZero(); assertThat(sync.source("tenant").orElseThrow().appliedRevision()).isZero();
    }

    @Test void existingUnmappedAppointmentRequiresExplicitAdoptionAndUsesEarlierBatchReferences() {
        apply(received(full()));
        var existing = tx.execute(ignored -> local.createAppointment(ADMIN, mapped(PERSON, "boss"), mapped(DEPARTMENT, "child"), mapped(POSITION, "position"), true));
        long revision = organization.revision("tenant");
        var batch = received(new OrganizationSyncDelta("hr", 1, 2, List.of(), List.of(), List.of(job("boss-child", "boss", "child", "position", null))));
        assertThat(preview(batch).plan().conflicts()).extracting(OrganizationSyncPlan.Conflict::code).containsExactly("ORGANIZATION_SYNC_ADOPTION_REQUIRED");
        var selection = new OrganizationSyncPlan.Selection(key(APPOINTMENT, "boss-child"), existing.id(), existing.revision());
        var saved = service.preflight(ADMIN, batch.context().id(), 3, List.of(selection));
        assertThat(saved.plan().ready()).isTrue(); assertThat(saved.plan().changedRecords()).isZero();
        service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), "采用既有任职");
        assertThat(mapped(APPOINTMENT, "boss-child")).isEqualTo(existing.id()); assertThat(count("organization_appointment")).isEqualTo(3);
        assertThat(organization.revision("tenant")).isEqualTo(revision);
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "directory_revision", "prepared_by", "ready", "plan_json"})
    void planRestorationRejectsIndexOrFullContentCorruption(String column) {
        var saved = preview(received(full()));
        switch (column) {
            case "digest" -> jdbc.update("UPDATE organization_sync_plan SET digest=?", "0".repeat(64));
            case "directory_revision" -> jdbc.update("UPDATE organization_sync_plan SET directory_revision=99");
            case "prepared_by" -> jdbc.update("UPDATE organization_sync_plan SET prepared_by='other'");
            case "ready" -> jdbc.update("UPDATE organization_sync_plan SET ready=FALSE");
            case "plan_json" -> jdbc.update("UPDATE organization_sync_plan SET plan_json=?", json.write(saved.plan()).replace("employee-subject", "changed-subject"));
            default -> throw new AssertionError(column);
        }
        assertThatThrownBy(() -> service.plan(ADMIN, saved.plan().id())).isInstanceOf(IllegalStateException.class);
        assertThat(count("organization_person")).isZero();
    }

    private JdbcOrganizationSyncPlanRepository.Saved preview(OrganizationSyncBatch batch) { return service.preflight(ADMIN, batch.context().id(), 3, List.of()); }
    private void apply(OrganizationSyncBatch batch) { var saved = preview(batch); assertThat(saved.plan().conflicts()).isEmpty(); service.apply(ADMIN, batch.context().id(), 3, saved.plan().id(), "已核对"); }
    private OrganizationSyncBatch received(OrganizationSyncDelta delta) {
        Instant at = now().minusSeconds(3);
        var batch = new OrganizationSyncBatch(new OrganizationSyncBatch.Context(UUID.randomUUID(), "tenant", "hr", delta.afterRevision(), configuration.require("tenant").digest("tenant"), "admin", at, null));
        tx.executeWithoutResult(ignored -> { sync.create(batch); batch.start(1, at.plusSeconds(1), at.plusSeconds(30)); sync.update(batch, 1); batch.receive(2, delta, at.plusSeconds(2)); sync.update(batch, 2); }); return batch;
    }
    private UUID mapped(OrganizationSyncKey.Kind kind, String id) { return sync.binding("tenant", key(kind, id)).orElseThrow().localId(); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static OrganizationSyncPlan.Selection selection(String external, OrganizationPerson person) { return new OrganizationSyncPlan.Selection(key(PERSON, external), person.id(), person.revision()); }
    private static OrganizationSyncDelta personDelta(long from, long to, String key, String subject, String name) { return new OrganizationSyncDelta("hr", from, to, List.of(), List.of(person(key, subject, name, true)), List.of()); }
    private static OrganizationSyncDelta full() {
        return new OrganizationSyncDelta("hr", 0, 1, List.of(unit("child", DEPARTMENT, "legal", "root", "employee-job"), unit("position", POSITION, "legal", null, null),
                unit("legal", LEGAL_ENTITY, null, null, null), unit("root", DEPARTMENT, "legal", null, "boss-job")),
                List.of(person("employee", "employee-subject", "员工", true), person("boss", "boss-subject", "主管", true)),
                List.of(job("employee-job", "employee", "child", "position", "boss-job"), job("boss-job", "boss", "root", "position", null)));
    }
    private static OrganizationSyncDelta.Unit unit(String id, OrganizationSyncKey.Kind kind, String legal, String parent, String head) {
        return new OrganizationSyncDelta.Unit(key(kind, id), id, key(LEGAL_ENTITY, legal), key(DEPARTMENT, parent), true, key(APPOINTMENT, head));
    }
    private static OrganizationSyncDelta.Person person(String id, String subject, String name, boolean eligible) { return new OrganizationSyncDelta.Person(key(PERSON, id), subject, name, true, eligible); }
    private static OrganizationSyncDelta.Appointment job(String id, String person, String department, String position, String supervisor) {
        return new OrganizationSyncDelta.Appointment(key(APPOINTMENT, id), key(PERSON, person), key(DEPARTMENT, department), key(POSITION, position), true, key(APPOINTMENT, supervisor));
    }
    private static OrganizationSyncKey key(OrganizationSyncKey.Kind kind, String id) { return id == null ? null : new OrganizationSyncKey(kind, id); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static void assertCode(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code); }
}
