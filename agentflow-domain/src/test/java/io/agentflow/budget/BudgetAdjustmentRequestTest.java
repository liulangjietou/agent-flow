package io.agentflow.budget;

import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.budget.BudgetAdjustmentContent.Type.*;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调整申请的冻结与批准边界：版本冲突、错误身份及失败校验不能修改原轮次。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentRequestTest {
    @Test void approvalSealsIntentWithoutChangingExternalLimitAndCanRestoreOldEvidence() {
        var request = draft(content(TRANSFER, "200"));
        freeze(request, 1, 1);
        var round = request.currentRound();
        request.approve(2, 1, 9, "finance-reviewer", NOW.plusSeconds(3600));
        assertThat(request.approval().applicationVersion()).isEqualTo(9);
        assertThat(request.version()).isEqualTo(3);
        assertThat(round.ledger().position("budget-source").limit()).isEqualTo(money("1000"));
        assertThat(round.changes().get(0).afterLimit()).isEqualTo(money("800"));
        fails("BUDGET_ADJUSTMENT_ALREADY_APPROVED", () -> request.revise(3, content(TRANSFER, "100")));
        fails("BUDGET_ADJUSTMENT_ALREADY_APPROVED", () -> freeze(request, 3, 2));
        assertThat(BudgetAdjustmentRequest.restore(request.state()).state()).isEqualTo(request.state());
        assertThat(round.toString()).doesNotContain("budget-source", "1000");
    }

    @Test void returnedDraftMustBeSubmittedAgainAndKeepsOriginalRoundUnchanged() {
        var request = draft(content(DECREASE, "200"));
        freeze(request, 1, 1);
        var first = request.currentRound();
        request.revise(2, content(DECREASE, "100"));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> request.approve(3, 1, 9, "reviewer", NOW));
        freeze(request, 3, 2);
        request.approve(4, 2, 12, "reviewer", NOW);
        assertThat(request.rounds().get(0)).isEqualTo(first);
        assertThat(first.changes().get(0).afterLimit()).isEqualTo(money("800"));
        assertThat(request.currentRound().changes().get(0).afterLimit()).isEqualTo(money("900"));
        assertThat(BudgetAdjustmentRequest.restore(request.state()).state()).isEqualTo(request.state());
    }

    @Test void insufficientStaleOrChangedEvidenceLeavesNoPartialRound() {
        var request = draft(content(DECREASE, "250.01"));
        var before = request.state();
        fails("BUDGET_ADJUSTMENT_INSUFFICIENT", () -> freeze(request, 1, 1));
        fails("INVALID_BUDGET_ADJUSTMENT_ROUND", () -> request.freeze(1, 1, catalog(), TARGET, ledger(request.content()), initiator(), NOW.plusSeconds(300)));
        fails("INVALID_BUDGET_ADJUSTMENT_ROUND", () -> request.freeze(1, 1, catalog(), "arbitrary-url", ledger(request.content()), initiator(), NOW));
        assertThat(request.state()).isEqualTo(before);
    }

    @Test void foreignApplicantAppointmentAndCurrencyCannotBecomeApprovalEvidence() {
        var request = draft(content(INCREASE, "10"));
        var own = initiator();
        var other = new InitiatorContext(own.appointmentId(), own.personId(), "bob", 1, ENTITY, "法人", own.departmentId(), "部门", own.positionId(), "岗位");
        fails("BUDGET_INITIATOR_MISMATCH", () -> request.freeze(1, 1, catalog(), TARGET, ledger(request.content()), other, NOW));
        var catalog = catalog();
        var stale = new FinanceCatalog("alice", "v1", NOW, catalog.legalEntities(), List.of(), List.of(), List.of(), List.of());
        fails("BUDGET_CATALOG_CHANGED", () -> request.freeze(1, 1, stale, TARGET, ledger(request.content()), own, NOW));
        var wrongApplicant = new FinanceCatalog("bob", "v1", NOW.plusSeconds(60), catalog.legalEntities(), List.of(), List.of(), List.of(), List.of());
        fails("BUDGET_CATALOG_CHANGED", () -> request.freeze(1, 1, wrongApplicant, TARGET, ledger(request.content()), own, NOW));
        var dollarContent = new BudgetAdjustmentContent(ENTITY, "调整", "理由", INCREASE, DATE, null, "budget-target", new Money(new BigDecimal("1"), "USD"));
        var dollarRequest = draft(dollarContent);
        fails("BUDGET_BASE_CURRENCY_REQUIRED", () -> dollarRequest.freeze(1, 1, catalog(), TARGET, ledger(dollarContent), own, NOW));
        assertThat(request.rounds()).isEmpty();
    }

    @Test void staleVersionsDraftApprovalAndForgedRestorationAreRejectedWithoutMutation() {
        var request = draft(content(DECREASE, "100"));
        fails("BUDGET_ADJUSTMENT_NOT_SUBMITTED", () -> request.approve(1, 1, 5, "reviewer", NOW));
        fails("CONCURRENCY_CONFLICT", () -> request.revise(2, content(DECREASE, "50")));
        freeze(request, 1, 1);
        var state = request.state();
        fails("INVALID_BUDGET_ADJUSTMENT", () -> request.approve(2, 2, 5, "reviewer", NOW));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> request.approve(2, 1, 5, "reviewer", NOW.minusSeconds(1)));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> BudgetAdjustmentRequest.restore(new BudgetAdjustmentRequest.State(state.id(), state.tenantId(), state.applicationId(), "bob", state.content(), state.rounds(), null, 2)));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> BudgetAdjustmentRequest.restore(new BudgetAdjustmentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), content(DECREASE, "50"), state.rounds(), null, 2)));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> BudgetAdjustmentRequest.restore(new BudgetAdjustmentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), List.of(state.rounds().get(0), state.rounds().get(0)), null, 4)));
        fails("INVALID_BUDGET_ADJUSTMENT", () -> BudgetAdjustmentRequest.restore(new BudgetAdjustmentRequest.State(state.id(), state.tenantId(), state.applicationId(), state.employeeId(), state.content(), state.rounds(), new BudgetAdjustmentRequest.Approval(2, 5, "reviewer", NOW), 3)));
        assertThat(request.state()).isEqualTo(state);
        var before = request.state();
        fails("INVALID_BUDGET_ADJUSTMENT", () -> request.approve(2, 1, 0, "reviewer", NOW));
        assertThat(request.state()).isEqualTo(before);
    }
}
