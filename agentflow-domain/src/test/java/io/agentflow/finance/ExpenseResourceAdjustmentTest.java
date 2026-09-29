package io.agentflow.finance;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpensePaymentReturn;
import io.agentflow.expense.ExpensePaymentReturns;
import io.agentflow.expense.ExpenseResourceAdjustment;
import io.agentflow.expense.ExpenseResourceAdjustmentBasis;
import io.agentflow.expense.ExpenseSettlement;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 完整取消需同时满足原挂账冲销、全部付款退回和原预算冲正，本地资源最后独立确认。
 * @author owlzhangfq@gmail.com
 */
class ExpenseResourceAdjustmentTest {
    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private static final String TARGET = "a".repeat(64);

    @Test void budgetAndResourcesCompleteSeparatelyWithoutRewritingOriginalSettlement() {
        var input = input(basis(false), "finance"); var queued = ExpenseResourceAdjustment.begin(input); var operation = applied(input.budget());
        assertThat(queued.resourcesReversed()).isFalse(); assertThat(queued.budgetReversal()).isNull();
        assertInvalid(() -> queued.applied(NOW));
        var ready = queued.budgetApplied(operation, NOW.plusSeconds(1)); assertThat(ready.status()).isEqualTo(ExpenseResourceAdjustment.Status.READY);
        assertThat(ready.resourcesReversed()).isFalse();
        var complete = ready.applied(NOW.plusSeconds(2)); assertThat(complete.resourcesReversed()).isTrue();
        assertThat(complete.input()).isEqualTo(input); assertThat(complete.budgetReversalVersion()).isEqualTo(operation.version());
        assertThat(complete.input().basis().settlement().status()).isEqualTo(ExpenseSettlement.Status.REVIEW_REQUIRED);
        assertThat(complete.input().basis().settlement().resourcesConsumed()).isTrue();
        assertInvalid(() -> complete.applied(NOW.plusSeconds(3)));
        var disputed = complete.requireReview("BUDGET_RECHECK_REQUIRED", NOW.plusSeconds(3));
        assertThat(disputed.resourcesReversed()).isTrue(); assertThat(disputed.budgetReversal()).isEqualTo(complete.budgetReversal());
        assertInvalid(() -> disputed.retryResources(operation, NOW.plusSeconds(4)));
    }

    @Test void resourceRetryPreservesTheFirstAcceptedBudgetReversalAndRejectsOtherCommands() {
        var input = input(basis(false), "finance"); var queued = ExpenseResourceAdjustment.begin(input); var operation = applied(input.budget());
        var blocked = queued.budgetApplied(operation, NOW.plusSeconds(1)).requireReview("EXPENSE_CONSUMPTION_CHANGED", NOW.plusSeconds(2));
        var retried = blocked.retryResources(operation, NOW.plusSeconds(3));
        assertThat(retried.status()).isEqualTo(ExpenseResourceAdjustment.Status.READY); assertThat(retried.resourcesReversed()).isFalse();
        assertThat(retried.budgetReversal()).isEqualTo(blocked.budgetReversal());
        assertThat(retried.budgetReversalVersion()).isEqualTo(blocked.budgetReversalVersion());
        var another = applied(input(basis(false), "finance").budget());
        assertInvalid(() -> blocked.retryResources(another, NOW.plusSeconds(3)));
        assertInvalid(() -> queued.budgetApplied(BudgetConsumptionReversalOperation.queue(input.budget(), NOW), NOW));
        assertInvalid(() -> new ExpenseResourceAdjustment(input, 1, ExpenseResourceAdjustment.Status.APPLIED, null, null, true, null, NOW, NOW));
    }

    @Test void fullActualBankReturnAndTheOriginalAccrualReversalAreBothRequired() {
        var valid = basis(false); var returns = valid.paymentReturns();
        var partial = new ExpensePaymentReturns(returns.request(), returns.version(), List.of(returns.entries().get(0)), true, returns.createdAt(), returns.updatedAt());
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), valid.consumption(), valid.accrualReversal(), partial, valid.paymentVoucher(), null));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), valid.consumption(), valid.accrualReversal(), null, valid.paymentVoucher(), null));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), valid.consumption(), null, returns, valid.paymentVoucher(), null));
        var another = basis(false);
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), another.consumption(), valid.accrualReversal(), returns, valid.paymentVoucher(), null));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), valid.consumption(), another.accrualReversal(), returns, valid.paymentVoucher(), null));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(valid.settlement(), valid.consumption(), valid.accrualReversal(), another.paymentReturns(), valid.paymentVoucher(), null));
    }

    @Test void zeroPayableUsesNoInventedBankReturnAndIndependentOriginalCashierCannotAuthorize() {
        var zero = basis(true); assertThat(zero.paymentReturns()).isNull(); assertThat(zero.settlement().input().payable()).isEqualTo(money("0"));
        assertThat(ExpenseResourceAdjustment.begin(input(zero, "finance")).status()).isEqualTo(ExpenseResourceAdjustment.Status.WAITING_BUDGET);
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(zero.settlement(), zero.consumption(), zero.accrualReversal(), basis(false).paymentReturns(), null, null));
        var paid = basis(false);
        assertInvalid(() -> input(paid, "alice")); assertInvalid(() -> input(paid, "cashier"));
        assertInvalid(() -> paid.requireAuthorization("finance", NOW.minusSeconds(10)));
    }

    @Test void authorizationCannotSwitchBudgetDestinationConsumedVersionOrAcceptedSource() {
        var input = input(basis(false), "finance"); var budget = input.budget();
        assertInvalid(() -> new ExpenseResourceAdjustment.Input(input.basis(), new BudgetConsumptionReversalOperation.Input(budget.consumedVersion() + 1, budget.command(), TARGET)));
        assertInvalid(() -> new ExpenseResourceAdjustment.Input(input.basis(), new BudgetConsumptionReversalOperation.Input(budget.consumedVersion(), budget.command(), "b".repeat(64))));
        assertInvalid(() -> new ExpenseResourceAdjustment.Input(input.basis(), input(basis(false), "finance").budget()));
    }

    @Test void missingOrPendingPaymentAccountingCannotReleaseExpenseResources() {
        var basis = basis(false); var voucher = basis.paymentVoucher();
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), basis.paymentReturns(), null, null));
        var queried = voucher.requestQuery(NOW.minusSeconds(3));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), basis.paymentReturns(), queried, null));
        var reversing = voucher.requestReversal(UUID.randomUUID(), NOW.minusSeconds(3));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), basis.paymentReturns(), reversing, null));
    }

    @Test void paymentVoucherReversalMustBeTheSameAccountingEvidenceAsTheBankReturn() {
        var basis = basis(false); var voucher = basis.paymentVoucher(); var record = reversal(voucher.input().command());
        var reversed = voucher.requestQuery(NOW.minusSeconds(5)).claim(NOW.minusSeconds(5), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(record.receipt().current()), NOW.minusSeconds(5));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), basis.paymentReturns(), reversed, record));
        var posting = record.receipt().reversal(); var credit = posting.lines().stream().filter(line -> line.side() == VoucherCommand.Side.CREDIT).findFirst().orElseThrow();
        var original = basis.paymentReturns();
        var returned = new ExpensePaymentReturnPort.ReturnItem(new ExpensePaymentReturnPort.BankReceipt("single-actual-bank-return", credit.amount(), NOW.minusSeconds(15)),
                new ExpensePaymentReturnPort.PayableCredit(posting.voucherReference(), credit.entryReference(), credit.accountCode(), credit.amount(), posting.accountingDate(), posting.postedAt()));
        var matching = new ExpensePaymentReturns(original.request(), original.version(), List.of(new ExpensePaymentReturns.Entry(UUID.randomUUID(), returned)), true, original.createdAt(), original.updatedAt());
        var confirmed = new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), matching, reversed, record);
        assertThat(confirmed.paymentReturns().totalReturned()).isEqualTo(basis.settlement().input().payable());
    }

    @Test void paymentAccountingCannotUseAnotherPayableAccountEvenWhenTotalsBalance() {
        var basis = basis(false); var original = basis.paymentVoucher().input().command(); var mapping = original.mapping();
        var changedMapping = new AccountMappingPort.Mapping(mapping.request(), "mapping-changed", mapping.observedAt(), mapping.validUntil(),
                mapping.entries().stream().map(entry -> entry.key().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE
                        ? new AccountMappingPort.Entry(entry.key(), "different-payable-account") : entry).toList());
        var changed = new VoucherCommand(original.id(), original.tenantId(), original.kind(), original.binding(), original.legalEntityId(), original.employeeId(),
                original.accountingDate(), original.totals(), original.period(), changedMapping, original.lines(), original.payment(), original.createdAt(), original.expiresAt());
        var posted = VoucherOperation.queue(new VoucherOperation.Input(changed, TARGET), changed.createdAt()).claim(changed.createdAt(), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(voucherObservation(changed, VoucherObservation.Status.POSTED, 1, changed.createdAt().plusSeconds(1))), changed.createdAt().plusSeconds(1));
        assertInvalid(() -> new ExpenseResourceAdjustmentBasis(basis.settlement(), basis.consumption(), basis.accrualReversal(), basis.paymentReturns(), posted, null));
    }

    private ExpenseResourceAdjustment.Input input(ExpenseResourceAdjustmentBasis basis, String actor) {
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(basis.legalEntityId(), "CNY", DATE), "2026-09", "period-v1",
                DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), NOW.minusSeconds(1), NOW.plusSeconds(300));
        var command = new BudgetConsumptionReversalCommand(UUID.randomUUID(), UUID.randomUUID(), basis.consumption().input().command(), basis.consumption().observation(),
                period, actor, "cancellation-evidence", "完整取消原报销", NOW, NOW.plusSeconds(120));
        return new ExpenseResourceAdjustment.Input(basis, new BudgetConsumptionReversalOperation.Input(basis.consumption().version(), command, TARGET));
    }
    private BudgetConsumptionReversalOperation applied(BudgetConsumptionReversalOperation.Input input) {
        var command = input.command(); var receipt = new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.APPLIED,
                NOW.plusSeconds(1), 3L, "budget-reversal", command.period().periodReference(), command.period().request().accountingDate(), NOW.plusSeconds(1), null);
        return BudgetConsumptionReversalOperation.queue(input, NOW).claim(NOW, Duration.ofSeconds(30)).complete(new FinanceResult.Success<>(receipt), NOW.plusSeconds(1));
    }
    private ExpenseResourceAdjustmentBasis basis(boolean zeroPayable) {
        var voucher = voucher(zeroPayable); var binding = voucher.binding(); var total = voucher.totals();
        var source = new VoucherPreparation.Source(voucher.tenantId(), BusinessReference.Type.EXPENSE, binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), voucher.employeeId());
        var position = new BudgetPrecheckPort.Request(binding.businessId(), binding.roundNo(), binding.businessVersion(), voucher.employeeId(), voucher.legalEntityId(), "CNY", voucher.accountingDate(),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("IT", "PROJECT", total.gross()))));
        var consumedCommand = new BudgetCommand(UUID.randomUUID(), voucher.tenantId(), BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "frozen"));
        var consumption = BudgetOperation.queue(new BudgetOperation.Input(consumedCommand, TARGET), NOW.minusSeconds(80)).claim(NOW.minusSeconds(80), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(new BudgetObservation(consumedCommand.id(), consumedCommand.digest(), BudgetObservation.Status.APPLIED, 2L, "consumed", NOW.minusSeconds(79), null)), NOW.minusSeconds(79));
        var returns = zeroPayable ? null : returns(voucher); var request = returns == null ? null : returns.request();
        var payment = request == null ? null : new ExpenseSettlement.Payment(request.command().id(), request.command().digest(), total.payable(),
                request.original().paymentReference(), request.original().receiptReference(), request.original().completedAt());
        var settlement = ExpenseSettlement.queue(new ExpenseSettlement.Input(source, total.gross(), total.offset(), voucher.id(), voucher.digest(), payment, NOW.minusSeconds(100)), NOW.minusSeconds(90))
                .consumed(consumedCommand.id(), NOW.minusSeconds(80)).budgetResolved(consumedCommand.id(), true, null, NOW.minusSeconds(79)).requireReview("VOUCHER_REVERSED", NOW.minusSeconds(4));
        return new ExpenseResourceAdjustmentBasis(settlement, consumption, reversal(voucher), returns, returns == null ? null : paymentVoucher(voucher, returns.request()), null);
    }
    private VoucherCommand voucher(boolean zero) {
        var original = VoucherReversalTest.command(VoucherCommand.Kind.EXPENSE_ACCRUAL); if (!zero) return original;
        var lines = original.lines().stream().filter(line -> line.account().role() != AccountMappingPort.Role.EMPLOYEE_PAYABLE).map(line ->
                line.account().role() == AccountMappingPort.Role.EMPLOYEE_RECEIVABLE ? new VoucherCommand.Line(line.lineNo(), line.account(), line.side(), money("100"), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId()) : line).toList();
        var request = new AccountMappingPort.Request(original.legalEntityId(), "CNY", lines.stream().map(VoucherCommand.Line::account).toList());
        var mapping = new AccountMappingPort.Mapping(request, "map-v1", original.mapping().observedAt(), original.mapping().validUntil(),
                original.mapping().entries().stream().filter(entry -> entry.key().role() != AccountMappingPort.Role.EMPLOYEE_PAYABLE).toList());
        return new VoucherCommand(original.id(), original.tenantId(), original.kind(), original.binding(), original.legalEntityId(), original.employeeId(), original.accountingDate(),
                new VoucherCommand.Totals(money("100"), money("6"), money("100")), original.period(), mapping, lines, null, original.createdAt(), original.expiresAt());
    }
    private VoucherReversalRecord reversal(VoucherCommand voucher) {
        var original = voucherObservation(voucher, VoucherObservation.Status.POSTED, 1, NOW.minusSeconds(10));
        var request = new VoucherReversalPort.Request(voucher, original); var current = voucherObservation(voucher, VoucherObservation.Status.REVERSED, 2, NOW.minusSeconds(5));
        var lines = voucher.lines().stream().map(line -> new VoucherReversalPort.Line("reverse-" + line.lineNo(), line.lineNo(), voucher.mapping().account(line.account()),
                line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT, line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        var posting = new VoucherReversalPort.Posting("reverse-posting", "reverse-voucher", "2026-09", DATE, NOW.minusSeconds(6), lines);
        var receipt = new VoucherReversalPort.Receipt(request, VoucherReversalPort.Status.VERIFIED, 1, NOW.minusSeconds(5), NOW.plusSeconds(100), current, posting);
        return new VoucherReversalRecord(UUID.randomUUID(), voucher.tenantId(), UUID.randomUUID(), 4, receipt, "finance", NOW.minusSeconds(4), "proof", "已实际冲销");
    }
    private VoucherOperation paymentVoucher(VoucherCommand accrual, ExpensePaymentReturnPort.Request payment) {
        var start = NOW.minusSeconds(90); var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(accrual.legalEntityId(), "CNY", DATE),
                "2026-09", "period-v1", DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), start, start.plusSeconds(60));
        var payable = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""); var bank = new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "bank");
        var request = new AccountMappingPort.Request(accrual.legalEntityId(), "CNY", List.of(payable, bank));
        var mapping = new AccountMappingPort.Mapping(request, "map-v1", start, start.plusSeconds(60),
                List.of(new AccountMappingPort.Entry(payable, payment.payableAccountCode()), new AccountMappingPort.Entry(bank, "bank-account")));
        var lines = List.of(new VoucherCommand.Line(1, payable, VoucherCommand.Side.DEBIT, payment.command().amount(), 0, null, null, null),
                new VoucherCommand.Line(2, bank, VoucherCommand.Side.CREDIT, payment.command().amount(), 0, null, null, null));
        var command = new VoucherCommand(UUID.randomUUID(), accrual.tenantId(), VoucherCommand.Kind.PAYMENT, accrual.binding(), accrual.legalEntityId(), accrual.employeeId(), DATE,
                new VoucherCommand.Totals(payment.command().amount(), money("0"), money("0")), period, mapping, lines, new VoucherCommand.PaymentProof(payment.command(), payment.original()), start, start.plusSeconds(60));
        return VoucherOperation.queue(new VoucherOperation.Input(command, TARGET), start).claim(start, Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(voucherObservation(command, VoucherObservation.Status.POSTED, 1, start.plusSeconds(1))), start.plusSeconds(1));
    }
    private VoucherObservation voucherObservation(VoucherCommand voucher, VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(voucher.id(), voucher.digest(), status, revision, at, "original-posting", "original-voucher", voucher.period().periodReference(),
                voucher.accountingDate(), voucher.totals().gross(), voucher.totals().gross(), voucher.createdAt(), null);
    }
    private ExpensePaymentReturns returns(VoucherCommand voucher) {
        var binding = voucher.binding(); var command = new PaymentCommand(UUID.randomUUID(), voucher.tenantId(), PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT,
                new PaymentCommand.Binding(binding.businessId(), binding.applicationId(), binding.roundNo(), binding.applicationVersion(), binding.businessVersion()), voucher.totals().payable(), "bank",
                new EmployeeAccountSnapshot(voucher.legalEntityId(), voucher.employeeId(), "employee-account", "****1234", TARGET, "v1"), "original-voucher",
                new PaymentCommand.Authorization("finance", "cashier", NOW.minusSeconds(200), NOW.minusSeconds(50)));
        var original = paymentObservation(command, PaymentObservation.Status.SUCCEEDED, 1, NOW.minusSeconds(100));
        var account = voucher.mapping().account(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""));
        var request = new ExpensePaymentReturnPort.Request(command, original, account);
        var proofs = List.of(returnItem("first", money("20"), account), returnItem("second", command.amount().minus(money("20")), account));
        var receipt = new ExpensePaymentReturnPort.Receipt(request, ExpensePaymentReturnPort.Status.RETURNED, 2, NOW.minusSeconds(5), NOW.plusSeconds(100),
                paymentObservation(command, PaymentObservation.Status.REVERSED, 2, NOW.minusSeconds(5)), proofs);
        var decision = new ExpensePaymentReturn(UUID.randomUUID(), voucher.tenantId(), UUID.randomUUID(), receipt, "finance", NOW.minusSeconds(4), "proof", "已全额退回");
        return ExpensePaymentReturns.open(request, NOW.minusSeconds(5)).register(decision);
    }
    private ExpensePaymentReturnPort.ReturnItem returnItem(String id, Money amount, String account) {
        return new ExpensePaymentReturnPort.ReturnItem(new ExpensePaymentReturnPort.BankReceipt("bank-" + id, amount, NOW.minusSeconds(15)),
                new ExpensePaymentReturnPort.PayableCredit("payable-" + id, "credit", account, amount, DATE, NOW.minusSeconds(14)));
    }
    private PaymentObservation paymentObservation(PaymentCommand command, PaymentObservation.Status status, long revision, Instant at) {
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, "original-payment", command.amount(), command.payee().accountDigest(), NOW.minusSeconds(100), "original-receipt", null);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(DomainException.class); }
}
