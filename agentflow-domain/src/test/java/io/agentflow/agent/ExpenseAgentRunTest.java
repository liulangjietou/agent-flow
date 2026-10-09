package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 决策权限、预算、人工节点及中断恢复的领域边界。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAgentRunTest {
    private final Instant now = Instant.parse("2026-10-09T00:00:00Z");
    private final UUID invoice = UUID.randomUUID();
    private ExpenseAgentRun run(int maximum) {
        return new ExpenseAgentRun(new ExpenseAgentRun.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "tenant", "alice", "核对资料",
                1, 1, new ExpenseAgentRun.Scope(List.of(1), List.of(invoice), List.of(), maximum), "a".repeat(64), now, now.plusSeconds(1800)));
    }
    @Test void unauthorizedReferenceNeverBecomesAnExecutableDecision() {
        var run = run(8); run.plan(now, 60);
        assertThatThrownBy(() -> run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.INVOICE, UUID.randomUUID(), null, "读取"), now.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.MODEL_RUNNING);
        assertThatThrownBy(() -> run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.POLICY, null, 2, "读取"), now.plusSeconds(1))).isInstanceOf(DomainException.class);
    }
    @Test void readsReturnToPlanningButStopAtTheAuthorizedBudget() {
        var run = run(1); run.plan(now, 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.EXPENSE, null, null, "核对费用"), now.plusSeconds(1));
        run.startTool(now.plusSeconds(2)); run.observed("真实已保存结果", now.plusSeconds(3));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.READY);
        assertThat(run.plan(now.plusSeconds(4), 60)).isNull();
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.LIMIT_REACHED);
        assertThat(run.state().steps()).hasSize(1);
    }
    @Test void unknownModelRequiresExplicitResumeAndNeverReusesOriginalCallIdentity() {
        var run = run(8); var original = run.plan(now, 60); run.recover(now.plusSeconds(60));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.INTERRUPTED);
        assertThatThrownBy(() -> run.resume(run.state().version(), "", false, now.plusSeconds(61))).isInstanceOf(DomainException.class);
        run.resume(run.state().version(), "已核对原记录", true, now.plusSeconds(61));
        assertThat(run.plan(now.plusSeconds(62), 60).id()).isNotEqualTo(original.id());
        assertThat(run.state().steps().get(0)).isEqualTo(original);
    }
    @Test void interruptedReadRetainsOriginalDecisionAndStepIdentity() {
        var run = run(8); var original = run.plan(now, 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.POLICY, null, 1, "核对制度"), now.plusSeconds(1));
        run.startTool(now.plusSeconds(2)); run.recover(now.plusSeconds(182));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.TOOL_READY);
        run.startTool(now.plusSeconds(183)); assertThat(run.last().id()).isEqualTo(original.id());
    }
    @Test void retryingFailedReadKeepsOriginalIdentityInsteadOfAskingModelAgain() {
        var run = run(8); var original = run.plan(now, 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.POLICY, null, 1, "核对制度"), now.plusSeconds(1));
        run.startTool(now.plusSeconds(2)); run.stop(ExpenseAgentRun.Status.FAILED, "EXTERNAL_READ_FAILED", now.plusSeconds(3));
        run.resume(run.state().version(), null, false, now.plusSeconds(4));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.TOOL_READY);
        assertThat(run.last().id()).isEqualTo(original.id()); assertThat(run.state().steps()).hasSize(1);
    }
    @Test void invoiceTaskMustMatchAndConfirmationContinuesIntoDraftConfirmation() {
        var run = run(8); run.plan(now, 60);
        run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.EXTRACT_INVOICE, invoice, null, "核对票面"), now.plusSeconds(1));
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.NEEDS_CONFIRMATION);
        assertThatThrownBy(() -> run.bind(ExpenseAgentRun.Action.EXTRACT_INVOICE, UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(2))).isInstanceOf(DomainException.class);
        var child = UUID.randomUUID(); run.bind(ExpenseAgentRun.Action.EXTRACT_INVOICE, invoice, child, now.plusSeconds(2));
        assertThat(run.state().childId()).isEqualTo(child);
        run.childConfirmed("本人确认的候选字段", now.plusSeconds(3));
        assertThat(run.last().decision().action()).isEqualTo(ExpenseAgentRun.Action.DRAFT);
        assertThat(run.state().status()).isEqualTo(ExpenseAgentRun.Status.NEEDS_CONFIRMATION);
        assertThat(run.state().steps().get(0).observation()).contains("本人确认");
    }
    @Test void askingForInformationDoesNotDispatchAnyToolUntilAnswered() {
        var run = run(8); run.plan(now, 60); run.decided(new ExpenseAgentRun.Decision(ExpenseAgentRun.Action.ASK_USER, null, null, "请说明费用用途"), now.plusSeconds(1));
        assertThatThrownBy(() -> run.startTool(now.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.resume(run.state().version(), " ", false, now.plusSeconds(2))).isInstanceOf(DomainException.class);
        run.resume(run.state().version(), "客户会议", false, now.plusSeconds(2));
        assertThat(run.state().answers()).containsExactly("客户会议");
    }
}
