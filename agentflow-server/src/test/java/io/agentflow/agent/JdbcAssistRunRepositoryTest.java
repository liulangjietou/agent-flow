package io.agentflow.agent;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证数据库中的租户绑定、不可变上下文、追加记录原子性与人工采纳时的实时版本保护。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENTFLOW_AGENT_TEST_URL:jdbc:h2:mem:agent-core;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_AGENT_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_AGENT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_AGENT_TEST_DRIVER:org.h2.Driver}"})
class JdbcAssistRunRepositoryTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final AssistInput.Reference SOURCE = new AssistInput.Reference("form:reason", "a".repeat(64));
    @Autowired AssistRunRepository runs;
    @Autowired ApplicationRepository applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persistsEveryTransitionWithoutChangingTheApprovalOrOriginalSuggestion() {
        var application = application("demo");
        var before = jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", application.id().toString());
        var run = queued(application);
        runs.create(run);
        assertThat(runs.find("other", run.id())).isEmpty();
        run = read(run);
        assertThat(run.createdAt()).isEqualTo(CREATED);
        run.start(1, CREATED); runs.update(run, 1);
        run = read(run);
        assertThat(run.startedAt()).isEqualTo(CREATED);
        run.complete(2, suggestion(), CREATED.plusSeconds(1)); runs.update(run, 2);
        run = read(run);
        run.adopt(3, application.version(), "manager", "人工修订摘要", "已检查源字段", CREATED.plusSeconds(2));
        runs.update(run, 3);
        var stored = read(run);
        assertThat(stored.status()).isEqualTo(AssistRun.Status.ADOPTED);
        assertThat(stored.review().acceptedText()).isEqualTo("人工修订摘要");
        assertThat(stored.suggestion().text()).isEqualTo("模型原始摘要");
        assertThat(stored.completedAt()).isEqualTo(CREATED.plusSeconds(1));
        assertThat(jdbc.queryForList("SELECT status FROM agent_assist_transition WHERE tenant_id=? AND run_id=? ORDER BY run_version",
                String.class, run.tenantId(), run.id().toString())).containsExactly("QUEUED", "RUNNING", "COMPLETED", "ADOPTED");
        assertThat(jdbc.queryForMap("SELECT * FROM approval_application WHERE id=?", application.id().toString())).isEqualTo(before);
    }

    @Test
    void creationRejectsCrossTenantAndStaleApplicationContext() {
        var app = application("other");
        var forged = AssistRun.queue(UUID.randomUUID(), "demo", "alice", CREATED,
                new AssistInput(app.id(), app.version(), app.roundNo(), List.of(SOURCE)), "prompt-v1");
        fails("AGENT_INPUT_CHANGED", () -> runs.create(forged));
        assertThat(runs.find("demo", forged.id())).isEmpty();
        var pending = queued(app);
        app.revise(1, "已修改的申请", Map.of("reason", "新内容")); applications.update(app, 1);
        fails("AGENT_INPUT_CHANGED", () -> runs.create(pending));
        assertThat(runs.find("other", pending.id())).isEmpty();
    }

    @Test
    void onlyOneConcurrentExecutorCanClaimTheSameQueuedVersion() {
        var run = queued(application("demo")); runs.create(run);
        var first = read(run); var second = read(run);
        first.start(1, CREATED); second.start(1, CREATED.plusSeconds(1));
        runs.update(first, 1);
        fails("CONCURRENCY_CONFLICT", () -> runs.update(second, 1));
        assertThat(read(run).startedAt()).isEqualTo(CREATED);
        assertThat(transitions(run)).isEqualTo(2);
    }

    @Test
    void immutableContextCannotBeReplacedByAnOtherwiseValidTransition() {
        var original = queued(application("demo")); runs.create(original);
        var otherApp = application("demo");
        var replacement = AssistRun.queue(original.id(), "demo", original.requestedBy(), CREATED,
                new AssistInput(otherApp.id(), otherApp.version(), otherApp.roundNo(), List.of(SOURCE)), "prompt-v1");
        replacement.start(1, CREATED);
        fails("CONCURRENCY_CONFLICT", () -> runs.update(replacement, 1));
        assertThat(read(original).input()).isEqualTo(original.input());
        assertThat(read(original).status()).isEqualTo(AssistRun.Status.QUEUED);
        assertThat(transitions(original)).isEqualTo(1);
    }

    @Test
    void failedJournalInsertRollsBackTheAggregateUpdate() {
        var run = queued(application("demo")); runs.create(run);
        jdbc.update("""
                INSERT INTO agent_assist_transition(tenant_id,run_id,run_version,status,state_json)
                SELECT tenant_id,run_id,2,'RUNNING',state_json FROM agent_assist_transition WHERE tenant_id=? AND run_id=?
                """, run.tenantId(), run.id().toString());
        run.start(1, CREATED);
        assertThatThrownBy(() -> runs.update(run, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(read(run).status()).isEqualTo(AssistRun.Status.QUEUED);
        assertThat(read(run).version()).isEqualTo(1);
    }

    @Test
    void adoptionRechecksApplicationVersionInsideTheWriteTransaction() {
        var app = application("demo"); var run = completed(app);
        run.adopt(3, app.version(), "manager", "旧摘要", null, CREATED.plusSeconds(3));
        app.revise(1, "并发修改", Map.of("reason", "变化后的内容")); applications.update(app, 1);
        fails("AGENT_INPUT_CHANGED", () -> runs.update(run, 3));
        var stored = read(run);
        assertThat(stored.status()).isEqualTo(AssistRun.Status.COMPLETED);
        assertThat(stored.review()).isNull();
        assertThat(transitions(run)).isEqualTo(3);
        stored.dismiss(3, "manager", "申请已变更", CREATED.plusSeconds(4)); runs.update(stored, 3);
        assertThat(read(run).status()).isEqualTo(AssistRun.Status.DISMISSED);
    }

    @Test
    void adoptionWaitsForAnInFlightApplicationChangeAndRejectsItsOldVersion() throws Exception {
        var app = application("demo"); var run = completed(app);
        run.adopt(3, app.version(), "manager", "待采纳摘要", null, CREATED.plusSeconds(3));
        var changed = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var adopting = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.update("UPDATE approval_application SET version=version+1 WHERE id=?", app.id().toString());
                changed.countDown();
                await(commit);
            }));
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            var adoption = executor.submit(() -> { adopting.countDown(); runs.update(run, 3); });
            assertThat(adopting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> adoption.get(150, TimeUnit.MILLISECONDS));
            commit.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertThatExceptionOfType(ExecutionException.class).isThrownBy(() -> adoption.get(5, TimeUnit.SECONDS))
                    .satisfies(exception -> {
                        assertThat(exception.getCause()).isInstanceOf(DomainException.class);
                        assertThat(((DomainException) exception.getCause()).code()).isEqualTo("AGENT_INPUT_CHANGED");
                    });
            assertThat(read(run).status()).isEqualTo(AssistRun.Status.COMPLETED);
            assertThat(transitions(run)).isEqualTo(3);
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void outerRollbackDoesNotLeaveAQueuedRunOrJournalEntry() {
        var run = queued(application("demo"));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> { runs.create(run); transaction.setRollbackOnly(); });
        assertThat(runs.find(run.tenantId(), run.id())).isEmpty();
        assertThat(transitions(run)).isZero();
    }

    @Test
    void failureSurvivesReloadWithoutAnInventedSuggestionOrHumanReview() {
        var run = queued(application("demo")); runs.create(run);
        run.start(1, CREATED); runs.update(run, 1);
        run.fail(2, AssistRun.Failure.MODEL_TIMEOUT, CREATED.plusSeconds(1)); runs.update(run, 2);
        var stored = read(run);
        assertThat(stored.status()).isEqualTo(AssistRun.Status.FAILED);
        assertThat(stored.failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
        assertThat(stored.suggestion()).isNull();
        assertThat(stored.review()).isNull();
        assertThat(transitions(run)).isEqualTo(3);
    }

    private Application application(String tenant) {
        return applications.save(Application.draft(UUID.randomUUID(), tenant, UUID.randomUUID().toString(),
                "agent-core-fixture", 1, "alice", "摘要持久化测试", Map.of("reason", "合成测试内容")));
    }
    private AssistRun queued(Application app) {
        return AssistRun.queue(UUID.randomUUID(), app.tenantId(), "alice", CREATED,
                new AssistInput(app.id(), app.version(), app.roundNo(), List.of(SOURCE)), "prompt-v1");
    }
    private AssistRun completed(Application app) {
        var run = queued(app); runs.create(run);
        run.start(1, CREATED); runs.update(run, 1);
        run.complete(2, suggestion(), CREATED.plusSeconds(1)); runs.update(run, 2);
        return run;
    }
    private AssistSuggestion suggestion() {
        return new AssistSuggestion("test", "test-model-v1", "prompt-v1",
                List.of(new AssistSuggestion.Claim("模型原始摘要", List.of(SOURCE))), new BigDecimal("0.5"));
    }
    private AssistRun read(AssistRun run) { return runs.find(run.tenantId(), run.id()).orElseThrow(); }
    private long transitions(AssistRun run) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_assist_transition WHERE tenant_id=? AND run_id=?",
                Long.class, run.tenantId(), run.id().toString());
    }
    private static void fails(String code, Runnable operation) {
        assertThatExceptionOfType(DomainException.class).isThrownBy(operation::run)
                .satisfies(exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Transaction test coordination timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
