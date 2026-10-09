package io.agentflow.agent;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 办理状态只组织真实步骤；重复、迟到、改版及步骤上限不能放宽费用或审批规则。
 * @author owlzhangfq@gmail.com
 */
class ExpenseHandlingTaskTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test void sameChildUpdatesOneStepAndReplayCannotRegressIt() {
        var task = task(); UUID run = UUID.randomUUID();
        observe(task, ExpenseHandlingTask.Tool.EXPLANATION, run, 1, "QUEUED", 1);
        observe(task, ExpenseHandlingTask.Tool.EXPLANATION, run, 2, "RUNNING", 1);
        observe(task, ExpenseHandlingTask.Tool.EXPLANATION, run, 3, "COMPLETED", 1);
        long version = task.state().version();
        observe(task, ExpenseHandlingTask.Tool.EXPLANATION, run, 2, "RUNNING", 1);
        assertThat(task.state().steps()).hasSize(1);
        assertThat(task.state().version()).isEqualTo(version);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.NEEDS_CONFIRMATION);
    }

    @Test void readonlyInspectionCannotHidePendingChecksAndNewVersionsInvalidateOldReadiness() {
        var task = task(); UUID check = UUID.randomUUID();
        observe(task, ExpenseHandlingTask.Tool.PRECHECK, check, 1, "QUEUED", 1);
        observe(task, ExpenseHandlingTask.Tool.EXPENSE, UUID.randomUUID(), 1, "READ", 1);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.WAITING);
        observe(task, ExpenseHandlingTask.Tool.PRECHECK, check, 3, "BLOCKED", 1);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.NEEDS_INFORMATION);
        observe(task, ExpenseHandlingTask.Tool.EXPENSE_SAVED, UUID.randomUUID(), 2, "SAVED", 2);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.OPEN);
        assertThat(task.state().financialVersion()).isEqualTo(2);
        assertThat(task.state().steps().get(0).outcome()).isEqualTo("BLOCKED");
    }

    @Test void historicalChildCannotJoinANewHandlingRecord() {
        var task = task();
        assertThat(task.observe(ExpenseHandlingTask.Tool.DRAFT, UUID.randomUUID(), 3, "COMPLETED", 1, 1, DIGEST, NOW.minusMillis(1), NOW)).isFalse();
        assertThat(task.state().version()).isEqualTo(1);
    }

    @Test void latestAttemptSupersedesEarlierFailureWithoutErasingHistory() {
        var task = task();
        observe(task, ExpenseHandlingTask.Tool.PRECHECK, UUID.randomUUID(), 3, "BLOCKED", 1);
        UUID retry = UUID.randomUUID();
        observe(task, ExpenseHandlingTask.Tool.PRECHECK, retry, 1, "QUEUED", 1);
        observe(task, ExpenseHandlingTask.Tool.PRECHECK, retry, 3, "READY", 1);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.OPEN);
        assertThat(task.state().steps()).extracting(ExpenseHandlingTask.Step::outcome).containsExactly("BLOCKED", "READY");
    }

    @Test void limitsStopRecordingWithoutTurningTheExpenseIntoAnApproval() {
        var task = task();
        for (int i = 0; i < ExpenseHandlingTask.MAX_STEPS; i++) observe(task, ExpenseHandlingTask.Tool.EXPENSE, UUID.randomUUID(), 1, "READ", 1);
        observe(task, ExpenseHandlingTask.Tool.EXPENSE, UUID.randomUUID(), 1, "READ", 1);
        assertThat(task.state().steps()).hasSize(ExpenseHandlingTask.MAX_STEPS);
        assertThat(task.state().status()).isEqualTo(ExpenseHandlingTask.Status.LIMIT_REACHED);
        assertThat(task.active()).isFalse();
    }

    @Test void restoreKeepsReferencesAndCloseChecksVersion() {
        var task = task(); observe(task, ExpenseHandlingTask.Tool.INVOICE, UUID.randomUUID(), 1, "READ", 1);
        var restored = ExpenseHandlingTask.restore(task.context(), task.state());
        assertThatThrownBy(() -> restored.close(1, NOW.plusSeconds(1))).hasMessageContaining("changed");
        restored.close(2, NOW.plusSeconds(1));
        assertThat(restored.state().steps()).isEqualTo(task.state().steps());
        assertThat(restored.state().status()).isEqualTo(ExpenseHandlingTask.Status.CLOSED);
    }

    private static ExpenseHandlingTask task() {
        return ExpenseHandlingTask.start(new ExpenseHandlingTask.Context(UUID.randomUUID(), "tenant", UUID.randomUUID(), UUID.randomUUID(), "alice", "核对本次出差费用", NOW), 1, 1);
    }
    private static void observe(ExpenseHandlingTask task, ExpenseHandlingTask.Tool tool, UUID reference, long sourceVersion, String outcome, long version) {
        task.observe(tool, reference, sourceVersion, outcome, version, version, DIGEST, NOW, NOW.plusSeconds(1));
    }
}
