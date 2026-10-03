package io.agentflow.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.FinanceJsonConfiguration;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.budget.BudgetAdjustmentCheck.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 实际数据库验证预算独立绑定、不可改写修订、持久预检及并发原子性。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private final String tenant = "budget-" + UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcBudgetAdjustmentRepository requests;
    private JdbcBudgetAdjustmentCheckRepository checks;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_PERSISTENCE_URL", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_PERSISTENCE_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_BUDGET_ADJUSTMENT_PERSISTENCE_PASSWORD", ""));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        requests = new JdbcBudgetAdjustmentRepository(jdbc, json);
        checks = new JdbcBudgetAdjustmentCheckRepository(jdbc, json, requests);
    }

    @Test void exactBindingAndOldRevisionsSurviveReconstructionAndRejectForeignOrWrongBusiness() {
        var request = create();
        var original = request.state();
        tx.executeWithoutResult(status -> { request.revise(1, content("60")); requests.update(request, 1, "alice", "REVISE"); });
        var reopened = new JdbcBudgetAdjustmentRepository(jdbc, json);
        assertThat(reopened.find(tenant, request.id()).orElseThrow().content().amount()).isEqualTo(money("60"));
        assertThat(reopened.find("foreign", request.id())).isEmpty();
        assertThat(json.read(jdbc.queryForObject("SELECT state_json FROM budget_adjustment_revision WHERE tenant_id=? AND request_id=? AND request_version=1",
                String.class, tenant, request.id().toString()), BudgetAdjustmentRequest.State.class)).isEqualTo(original);
        var fake = BudgetAdjustmentRequest.draft(UUID.randomUUID(), tenant, request.applicationId(), "alice", content("50"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.create(fake, "alice"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(request, 1, "alice", "REVISE"))).isInstanceOf(DomainException.class);
    }

    @Test void revisionAppendFailureRollsBackTheCurrentState() {
        var request = create();
        var before = request.state();
        request.revise(1, content("60"));
        jdbc.update("INSERT INTO budget_adjustment_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES(?,?,2,'alice','SYNTHETIC_COLLISION',?)",
                tenant, request.id().toString(), json.write(request.state()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(request, 1, "alice", "REVISE"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(before);
    }

    @Test void oneConcurrentRevisionWinsAndCannotLeaveASecondAudit() throws Exception {
        var original = create();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of("60", "50").stream().map(amount -> executor.submit(() -> {
                var changed = BudgetAdjustmentRequest.restore(original.state());
                changed.revise(1, content(amount));
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start timed out");
                try {
                    tx.executeWithoutResult(status -> requests.update(changed, 1, "alice", "REVISE"));
                    return "SAVED";
                } catch (DomainException conflict) { return conflict.code(); }
            })).toList();
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "CONCURRENCY_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_revision WHERE tenant_id=? AND request_id=?", Integer.class, tenant, original.id().toString())).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test void repositoryRejectsRewrittenPastRoundsAndMislabelledApproval() {
        var request = create();
        var evidence = evidence(request);
        request.freeze(1, 1, evidence.catalog(), "a".repeat(64), evidence.preview().ledger(), initiator(), NOW.plusSeconds(1));
        tx.executeWithoutResult(status -> requests.update(request, 1, "alice", "SUBMIT"));
        var before = request.state();
        var forged = BudgetAdjustmentRequest.draft(request.id(), tenant, request.applicationId(), "alice", content("50"));
        var changedEvidence = evidence(forged);
        forged.freeze(1, 1, changedEvidence.catalog(), "a".repeat(64), changedEvidence.preview().ledger(), initiator(), NOW.plusSeconds(1));
        forged.revise(2, content("50"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(forged, 2, "alice", "REVISE"))).isInstanceOf(DomainException.class);
        request.approve(2, 1, 8, "reviewer", NOW.plusSeconds(2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.update(request, 2, "alice", "APPROVE"))).isInstanceOf(DomainException.class);
        assertThat(requests.find(tenant, request.id()).orElseThrow().state()).isEqualTo(before);
        tx.executeWithoutResult(status -> requests.update(request, 2, "reviewer", "APPROVE"));
        assertThat(new JdbcBudgetAdjustmentRepository(jdbc, json).find(tenant, request.id()).orElseThrow().state()).isEqualTo(request.state());
    }

    @Test void queuedClaimReadyAndLaterAttemptPersistWithoutOverwritingEarlierFacts() {
        var request = create();
        var queued = queue(input(request, 1), NOW);
        tx.executeWithoutResult(status -> checks.create(queued));
        assertThat(checks.active(tenant, request.id())).isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.create(queue(input(request, 2), NOW)))).isInstanceOf(DomainException.class);
        var running = queued.start(NOW, NOW.plusSeconds(60));
        tx.executeWithoutResult(status -> checks.update(running));
        var done = running.finish(Result.ready(evidence(request)), NOW.plusSeconds(2));
        tx.executeWithoutResult(status -> checks.update(done));
        var reopened = new JdbcBudgetAdjustmentCheckRepository(jdbc, json, requests);
        assertThat(reopened.find(tenant, queued.input().id())).contains(done);
        assertThat(reopened.find("foreign", queued.input().id())).isEmpty();
        assertThat(reopened.active(tenant, request.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_check_revision WHERE tenant_id=? AND job_id=?", Integer.class, tenant, queued.input().id().toString())).isEqualTo(3);
        var next = queue(input(request, 2), NOW.plusSeconds(3));
        tx.executeWithoutResult(status -> reopened.create(next));
        assertThat(reopened.latestAttempt(tenant, request.id())).isEqualTo(2);
        assertThat(reopened.latestId(tenant, request.id())).contains(next.input().id());
        assertThat(reopened.find(tenant, done.input().id())).contains(done);
    }

    @Test void staleContentForgedLeaseAndLateSuccessCannotReplaceOriginalCheck() {
        var request = create();
        var input = input(request, 1);
        var altered = new Input(input.id(), tenant, request.id(), request.applicationId(), "alice", 1, 1, 1, 1, input.initiator(), input.targetDigest(), content("60"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.create(queue(altered, NOW)))).isInstanceOf(DomainException.class);
        var queued = queue(input, NOW);
        tx.executeWithoutResult(status -> checks.create(queued));
        var running = queued.start(NOW, NOW.plusSeconds(30));
        tx.executeWithoutResult(status -> checks.update(running));
        var forged = new BudgetAdjustmentCheck(input, 3, Status.UNAVAILABLE, NOW, NOW, NOW.plusSeconds(60), NOW.plusSeconds(5), Result.unavailable("TIMEOUT"));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.update(forged))).isInstanceOf(DomainException.class);
        assertThat(checks.find(tenant, input.id())).contains(running);
        var timeout = running.finish(Result.ready(evidence(request)), NOW.plusSeconds(30));
        tx.executeWithoutResult(status -> checks.update(timeout));
        assertThat(checks.find(tenant, input.id()).orElseThrow().result().code()).isEqualTo("TIMEOUT");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.update(running.finish(Result.ready(evidence(request)), NOW.plusSeconds(2)))))
                .isInstanceOf(DomainException.class);
        jdbc.update("UPDATE budget_adjustment_check_job SET application_version=2 WHERE tenant_id=? AND id=?", tenant, input.id().toString());
        assertThatThrownBy(() -> checks.find(tenant, input.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void inconsistentSourceIdentityCannotBecomeQueuedEvidenceEvenIfVisibleContentMatches() {
        var request = create();
        var state = request.state();
        var corrupted = new BudgetAdjustmentRequest.State(state.id(), state.tenantId(), state.applicationId(), "bob",
                state.content(), state.rounds(), state.approval(), state.version());
        jdbc.update("UPDATE budget_adjustment SET state_json=? WHERE tenant_id=? AND id=?", json.write(corrupted), tenant, request.id().toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> checks.create(queue(input(request, 1), NOW)))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_adjustment_check_job WHERE tenant_id=?", Integer.class, tenant)).isZero();
    }

    private BudgetAdjustmentRequest create() {
        var request = BudgetAdjustmentRequest.draft(UUID.randomUUID(), tenant, UUID.randomUUID(), "alice", content("70"));
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','预算调整','{}','DRAFT',1,1,'BUDGET_ADJUSTMENT',?)",
                    request.applicationId().toString(), tenant, UUID.randomUUID().toString(), request.id().toString());
            requests.create(request, "alice");
        });
        return request;
    }

    private BudgetAdjustmentContent content(String amount) {
        return new BudgetAdjustmentContent(entity, "预算调整", "业务额度调拨", BudgetAdjustmentContent.Type.TRANSFER,
                LocalDate.parse("2026-09-29"), "source", "target", money(amount));
    }
    private Input input(BudgetAdjustmentRequest request, long attempt) {
        return new Input(UUID.randomUUID(), tenant, request.id(), request.applicationId(), "alice", 1, request.version(), request.rounds().size() + 1, attempt,
                initiator(), "a".repeat(64), request.content());
    }
    private InitiatorContext initiator() { return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"); }
    private Evidence evidence(BudgetAdjustmentRequest original) {
        var catalog = new FinanceCatalog("alice", "v1", NOW.plusSeconds(300), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        var positions = original.content().ledgerRequest("alice").budgetReferences().stream().map(reference -> new BudgetLedgerPort.Position(entity, reference, "预算", "v1", "2026",
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), BudgetLedgerPort.PeriodStatus.OPEN, money("1000"), money("300"), money("450"))).toList();
        var ledger = new BudgetLedgerPort.Snapshot(original.content().ledgerRequest("alice"), "ledger-v1", NOW, NOW.plusSeconds(300), positions);
        var preview = BudgetAdjustmentRequest.restore(original.state());
        preview.freeze(original.version(), original.rounds().size() + 1, catalog, "a".repeat(64), ledger, initiator(), NOW.plusSeconds(1));
        return new Evidence(catalog, preview.currentRound(), NOW.plusSeconds(300));
    }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
}
