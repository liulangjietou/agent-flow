package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import io.agentflow.notification.NotificationTexts;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 部分差额必须逐项来自原核销、预算消费和挂账，借贷平衡不能替代原位置核对。
 * @author owlzhangfq@gmail.com
 */
class ExpenseAdjustmentFinancialSourceTest {
    private static final Instant NOW = Instant.parse("2026-09-30T18:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String TARGET = "a".repeat(64);

    @Test void independentDeltaUsesOriginalAccountsTaxPositionsAndUnchangedBudgetIdentities() {
        var fixture = fixture("30"); var change = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4");
        var source = source(fixture, change);
        assertThat(source.voucherLines()).extracting(VoucherReversalCommand.Line::originalLineNo).containsExactly(1, 2, 3, 4, 6);
        assertThat(source.voucherLines()).extracting(VoucherReversalCommand.Line::amount).containsExactly(money("10.8"), money("1.2"), money("7.2"), money("0.8"), money("20"));
        assertThat(source.voucherLines()).extracting(VoucherReversalCommand.Line::side).containsExactly(VoucherCommand.Side.CREDIT, VoucherCommand.Side.CREDIT, VoucherCommand.Side.CREDIT, VoucherCommand.Side.CREDIT, VoucherCommand.Side.DEBIT);
        assertThat(source.voucherLines()).extracting(VoucherReversalCommand.Line::accountCode).containsExactly("old-EXPENSE", "old-DEDUCTIBLE_TAX", "old-EXPENSE", "old-DEDUCTIBLE_TAX", "old-EMPLOYEE_PAYABLE");
        assertThat(source.budgetReduction()).extracting(value -> value.cost().amount()).containsExactly(money("12"), money("8"));
        assertThat(source.budgetBefore()).isEqualTo(fixture.budget().input().command().position().allocations());
        assertThat(source.budgetAfter()).extracting(value -> value.cost().amount()).containsExactly(money("48"), money("32"));
        assertThat(source.change().before().original()).isEqualTo(fixture.report().state());
        assertThat(source.toString()).doesNotContain("alice", "100.00", "old-EXPENSE");
        assertThatThrownBy(() -> source.voucherLines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void laterDeltaKeepsOriginalLineNumbersAndNeverReversesAnAlreadyReleasedAmount() {
        var fixture = fixture("30"); var first = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4");
        var second = source(fixture, reduce(first.after(), "20", "1"));
        assertThat(second.voucherLines()).extracting(VoucherReversalCommand.Line::originalLineNo).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(second.voucherLines()).extracting(VoucherReversalCommand.Line::amount).containsExactly(money("34.2"), money("1.8"), money("22.8"), money("1.2"), money("10"), money("50"));
        assertThat(second.budgetReduction()).extracting(value -> value.cost().amount()).containsExactly(money("36"), money("24"));
        assertThat(second.budgetBefore()).extracting(value -> value.cost().amount()).containsExactly(money("48"), money("32"));
        assertThat(second.budgetAfter()).extracting(value -> value.cost().amount()).containsExactly(money("12"), money("8"));
        assertThat(second.voucherLines().get(4).advanceId()).isEqualTo(fixture.report().currentRound().advanceOffsets().get(0).advanceId());
    }

    @Test void zeroPayableAndCompleteCancellationKeepBudgetPositionsWithoutInventingBankLines() {
        var fixture = fixture("100"); var change = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "0", "0");
        var source = source(fixture, change);
        assertThat(source.change().bankReturn()).isEqualTo(money("0"));
        assertThat(source.voucherLines()).hasSize(5); assertThat(source.voucherLines()).noneMatch(value -> value.accountCode().contains("BANK") || value.accountCode().contains("PAYABLE"));
        assertThat(source.budgetAfter()).hasSize(2); assertThat(source.budgetAfter()).allSatisfy(value -> assertThat(value.cost().amount()).isEqualTo(money("0")));
        assertThat(source.budgetAfter()).extracting(BudgetPrecheckPort.Allocation::allocationNo).containsExactly(1, 2);
    }

    @Test void pendingBudgetUnpostedVoucherAndUnconsumedResourcesCannotBecomeAdjustmentSources() {
        var fixture = fixture("30"); var change = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4");
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), BudgetOperation.queue(fixture.budget().input(), NOW), fixture.accrual()));
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), VoucherOperation.queue(fixture.accrual().input(), NOW)));
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, ExpenseSettlement.queue(fixture.settlement().input(), NOW), fixture.budget(), fixture.accrual()));
        var disputed = fixture.accrual().requestQuery(NOW.plusSeconds(1));
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), disputed));
        var review = fixture.settlement().requireReview("EXPENSE_PAYMENT_RETURN_REVIEW_REQUIRED", NOW.plusSeconds(1));
        assertThatCode(() -> new ExpenseAdjustmentFinancialSource(change, review, fixture.budget(), fixture.accrual())).doesNotThrowAnyException();
    }

    @Test void equalBudgetTotalsCannotReplaceOriginalCostPositionsOrTheConsumeAction() {
        var fixture = fixture("30"); var change = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4");
        var command = fixture.budget().input().command(); var position = command.position();
        var allocations = position.allocations().stream().map(value -> new BudgetPrecheckPort.Allocation(value.expenseLineNo(), value.allocationNo(), value.categoryCode(),
                new CostAllocation("foreign-" + value.cost().costCenter(), value.cost().projectCode(), value.cost().amount()))).toList();
        var replaced = new BudgetPrecheckPort.Request(position.reportId(), position.roundNo(), position.financialVersion(), position.employeeId(), position.legalEntityId(), position.baseCurrency(), position.accountingDate(), allocations);
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), applied(new BudgetCommand(command.id(), command.tenantId(), command.action(), replaced, command.expected())), fixture.accrual()));
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), applied(new BudgetCommand(command.id(), command.tenantId(), BudgetCommand.Action.ADJUST, position, command.expected())), fixture.accrual()));
    }

    @Test void balancedVoucherWithAnotherCostObjectFailsEvenWhenItsDigestIsConsistent() {
        var fixture = fixture("30"); var command = fixture.accrual().input().command();
        var lines = command.lines().stream().map(value -> value.lineNo() == 1 ? new VoucherCommand.Line(value.lineNo(), value.account(), value.side(), value.amount(), value.sourceLineNo(), "foreign", value.projectCode(), value.advanceId()) : value).toList();
        var changed = copy(command, command.binding(), lines); var original = fixture.settlement(); var input = original.input();
        var settlement = new ExpenseSettlement(new ExpenseSettlement.Input(input.source(), input.gross(), input.offsets(), changed.id(), changed.digest(), input.payment(), input.fundingConfirmedAt()),
                original.version(), original.status(), original.resourcesConsumed(), original.budgetOperationId(), original.issue(), original.createdAt(), original.updatedAt());
        invalid(() -> new ExpenseAdjustmentFinancialSource(reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4"), settlement, fixture.budget(), posted(changed)));
    }

    @Test void sourceRejectsAnotherRoundApprovalVersionAndAnotherReportWithTheSameMoney() {
        var fixture = fixture("30"); var original = fixture.accrual().input().command(); var binding = original.binding();
        var wrong = copy(original, new VoucherCommand.Binding(binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion() + 1, binding.businessVersion()), original.lines());
        var change = reduce(ExpenseAdjustmentAmounts.from(fixture.report()), "80", "4");
        invalid(() -> new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), posted(wrong)));
        invalid(() -> new ExpenseAdjustmentFinancialSource(reduce(ExpenseAdjustmentAmounts.from(fixture("30").report()), "80", "4"), fixture.settlement(), fixture.budget(), fixture.accrual()));
    }

    static Fixture fixture(String offset) {
        var entity = UUID.randomUUID(); var line = new ExpenseLine(1, "OFFICE", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, money("100"), money("6"), List.of(), null,
                List.of(new CostAllocation("A", null, money("60")), new CostAllocation("B", "PROJECT", money("40"))), "已核销费用", null);
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", new ExpenseContent(entity, ExpenseContent.Type.DAILY, "独立调整", List.of(line), List.of(new AdvanceOffset(UUID.randomUUID(), money(offset)))));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(entity, "alice", "original-account", "****1234", TARGET, "v1"), Map.of(1,
                new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "local", DATE), new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("100"), money("100"), ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "tax", "policy"), money("6"))), "alice", NOW.minusSeconds(30));
        var app = Application.restore(report.applicationId(), "demo", "SOURCE", "expense", 1, "alice", "原批准", ExpenseFormContract.submittedPayload(report.currentRound()), ApplicationStatus.APPROVED, 1, 5, null, null, NotificationTexts.EMPTY, new BusinessReference(BusinessReference.Type.EXPENSE, report.id()));
        var control = ExpenseSubmissionControl.submitted(new ExpenseSubmissionControl.Input("demo", report.id(), report.applicationId(), "alice", 1, 2, UUID.randomUUID(), DATE, false, Map.of("finance", ExpenseProcessPolicy.Stage.FINANCE_REVIEW)), NOW.minusSeconds(30));
        var plan = VoucherSource.expense(app, report, control);
        var period = new AccountingPeriodPort.OpenPeriod(plan.periodRequest(), "2026-09", "period-v1", DATE.withDayOfMonth(1), DATE, NOW.minusSeconds(30), NOW.plusSeconds(300));
        var mapping = new AccountMappingPort.Mapping(plan.mappingRequest(), "original-map", NOW.minusSeconds(30), NOW.plusSeconds(300), plan.mappingRequest().keys().stream().map(key -> new AccountMappingPort.Entry(key, "old-" + key.role())).toList());
        var voucher = plan.prepare(UUID.randomUUID(), period, mapping, NOW.minusSeconds(20)); var accrual = posted(voucher);
        var budget = applied(new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.CONSUME, BudgetPrecheckPort.Request.fromCurrent(report, DATE), new BudgetCommand.Expected(1, "frozen")));
        var source = new VoucherPreparation.Source("demo", BusinessReference.Type.EXPENSE, report.id(), report.applicationId(), 1, 5, report.version(), "alice");
        var payment = plan.totals().payable().value().signum() == 0 ? null : new ExpenseSettlement.Payment(UUID.randomUUID(), TARGET, plan.totals().payable(), "bank", "receipt", NOW.minusSeconds(15));
        var settlement = ExpenseSettlement.queue(new ExpenseSettlement.Input(source, plan.totals().gross(), plan.totals().offset(), voucher.id(), voucher.digest(), payment, NOW.minusSeconds(15)), NOW.minusSeconds(12))
                .consumed(budget.input().command().id(), NOW.minusSeconds(10)).budgetResolved(budget.input().command().id(), true, null, NOW.minusSeconds(9));
        return new Fixture(report, settlement, budget, accrual);
    }
    private static BudgetOperation applied(BudgetCommand command) {
        return BudgetOperation.queue(new BudgetOperation.Input(command, TARGET), NOW.minusSeconds(10)).claim(NOW.minusSeconds(10), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.APPLIED, 2L, "consumed", NOW.minusSeconds(9), null)), NOW.minusSeconds(9));
    }
    private static VoucherOperation posted(VoucherCommand command) {
        var at = NOW.minusSeconds(19);
        return VoucherOperation.queue(new VoucherOperation.Input(command, TARGET), command.createdAt()).claim(command.createdAt(), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, at, "posting", "voucher", "2026-09", DATE, command.totals().gross(), command.totals().gross(), at, null)), at);
    }
    private static VoucherCommand copy(VoucherCommand original, VoucherCommand.Binding binding, List<VoucherCommand.Line> lines) {
        return new VoucherCommand(original.id(), original.tenantId(), original.kind(), binding, original.legalEntityId(), original.employeeId(), original.accountingDate(), original.totals(), original.period(), original.mapping(), lines, null, original.createdAt(), original.expiresAt());
    }
    private static ExpenseAdjustmentAmounts.Change reduce(ExpenseAdjustmentAmounts value, String gross, String tax) { return value.reduce(List.of(new ExpenseReport.Reduction(1, money(gross), money(tax)))); }
    private static ExpenseAdjustmentFinancialSource source(Fixture fixture, ExpenseAdjustmentAmounts.Change change) { return new ExpenseAdjustmentFinancialSource(change, fixture.settlement(), fixture.budget(), fixture.accrual()); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
    /**
     * 原批准与三份独立财务依据，测试替换其一不能借用另一来源的金额。
     * @author owlzhangfq@gmail.com
     */
    record Fixture(ExpenseReport report, ExpenseSettlement settlement, BudgetOperation budget, VoucherOperation accrual) { }
}
