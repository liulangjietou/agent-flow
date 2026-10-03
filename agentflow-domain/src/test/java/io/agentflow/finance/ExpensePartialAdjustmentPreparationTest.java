package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 独立准备按本侧原状态、真实原件及最早有效期生成命令，另一侧正常推进不构成失效。
 * @author owlzhangfq@gmail.com
 */
class ExpensePartialAdjustmentPreparationTest {
    private static final Instant NOW = Instant.parse("2026-09-30T18:30:10Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void eachSideConsumesItsOwnPreparationAndKeepsTheOtherCommand() {
        var original = ExpensePartialAdjustmentTest.adjustment();
        var budget = ready(original, ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        var accrual = ready(original, ExpensePartialAdjustmentPreparation.Side.ACCRUAL, NOW);
        var first = budget.authorize(original, NOW.plusSeconds(1)); var queued = first.authorizedAdjustment(original);
        var second = accrual.authorize(queued, NOW.plusSeconds(2)); var both = second.authorizedAdjustment(queued);
        assertThat(both.budget()).isEqualTo(queued.budget());
        assertThat(both.budget().input().command().id()).isEqualTo(budget.input().id());
        assertThat(both.accrual().input().command().id()).isEqualTo(accrual.input().id());
        assertThat(both.input()).isEqualTo(original.input());
        invalid(() -> first.authorize(original, NOW.plusSeconds(2)));
        invalid(() -> first.authorizedAdjustment(queued));
    }

    @Test void completionOrChangedSelectedOperationInvalidatesOldPreparation() {
        var original = ExpensePartialAdjustmentTest.adjustment();
        var ready = ready(original, ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        var queued = original.authorizeBudget(ExpensePartialAdjustmentTest.budget(original, NOW), NOW);
        invalid(() -> ready.authorize(queued, NOW.plusSeconds(1)));
        invalid(() -> queue(queued, ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW.plusSeconds(1)));
        var running = queued.withBudget(queued.budget().claim(NOW.plusSeconds(1), LEASE), NOW.plusSeconds(1));
        var unknown = running.withBudget(running.budget().unavailable(BudgetConsumptionReductionOperation.Failure.TIMEOUT, NOW.plusSeconds(2)), NOW.plusSeconds(2));
        invalid(() -> queue(unknown, ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW.plusSeconds(3)));
        invalid(() -> ready.authorize(original.requireReview("SOURCE_CHANGED", NOW.plusSeconds(1)), NOW.plusSeconds(2)));
    }

    @Test void evidenceExpiryIsExclusiveAndCannotBeExtendedByNewAuthorizationTime() {
        var ready = ready(ExpensePartialAdjustmentTest.adjustment(), ExpensePartialAdjustmentPreparation.Side.ACCRUAL, NOW);
        var expiry = ready.evidence().expiresAt(); assertThat(expiry).isEqualTo(NOW.plusSeconds(300));
        assertThat(ready.usable(expiry.minusNanos(1))).isTrue(); assertThat(ready.usable(expiry)).isFalse();
        invalid(() -> ready.authorize(ready.input().adjustment(), expiry));
        var authorized = ready.authorize(ready.input().adjustment(), NOW.plusSeconds(200));
        assertThat(authorized.authorizedAdjustment(ready.input().adjustment()).accrual().input().command().expiresAt()).isEqualTo(expiry);
    }

    @Test void earlyPeriodExpiryLimitsBothSidesAndFutureOrDifferentDateIsRejected() {
        var queued = queue(ExpensePartialAdjustmentTest.adjustment(), ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        var claimed = queued.claim(queued.input().adjustment().input().basis().funding(), NOW, LEASE);
        var evidence = evidence(queued, NOW); var period = evidence.period();
        var shorter = new AccountingPeriodPort.OpenPeriod(period.request(), period.periodReference(), period.sourceVersion(), period.startsOn(), period.endsOn(), NOW, NOW.plusSeconds(5));
        var ready = claimed.ready(new ExpensePartialAdjustmentPreparation.Evidence(evidence.source(), evidence.bank(), shorter, NOW), NOW);
        assertThat(ready.evidence().expiresAt()).isEqualTo(NOW.plusSeconds(5));
        invalid(() -> claimed.ready(evidence, NOW.minusSeconds(1)));
        var tomorrow = period.request().accountingDate().plusDays(1);
        var wrong = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(period.request().legalEntityId(), "CNY", tomorrow), "next", "v1", tomorrow, tomorrow, NOW, NOW.plusSeconds(300));
        invalid(() -> claimed.ready(new ExpensePartialAdjustmentPreparation.Evidence(evidence.source(), evidence.bank(), wrong, NOW), NOW));
    }

    @Test void expiredReadDoesNotCreateReadyEvidenceAndOldCommandsRemainUntouched() {
        var queued = queue(ExpensePartialAdjustmentTest.adjustment(), ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        var claimed = queued.claim(queued.input().adjustment().input().basis().funding(), NOW, LEASE);
        var failed = claimed.ready(evidence(queued, NOW), NOW.plusSeconds(30));
        assertThat(failed.status()).isEqualTo(ExpensePartialAdjustmentPreparation.Status.UNAVAILABLE); assertThat(failed.issue()).isEqualTo("TIMEOUT");
        assertThat(failed.evidence()).isNull(); assertThat(failed.input().adjustment().budget()).isNull();
        invalid(() -> failed.authorize(queued.input().adjustment(), NOW.plusSeconds(31)));
    }

    @Test void freshEvidenceCannotReplaceRegisteredBankReturnsOrOriginalExpense() {
        var queued = queue(ExpensePartialAdjustmentTest.adjustment(), ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        var claimed = queued.claim(queued.input().adjustment().input().basis().funding(), NOW, LEASE);
        var foreign = queue(ExpensePartialAdjustmentTest.adjustment(), ExpensePartialAdjustmentPreparation.Side.BUDGET, NOW);
        invalid(() -> claimed.ready(evidence(foreign, NOW), NOW));
        var evidence = evidence(queued, NOW); var bank = evidence.bank();
        var stale = new ExpensePaymentReturnPort.Receipt(bank.request(), bank.status(), bank.revision(), NOW, queued.input().adjustment().input().basis().funding().payment().observation().observedAt().plusSeconds(300), queued.input().adjustment().input().basis().funding().payment().observation(), bank.returns());
        invalid(() -> new ExpensePartialAdjustmentPreparation.Evidence(evidence.source(), stale, evidence.period(), NOW));
    }

    @Test void applicantAndOriginalCashierCannotPrepareOrChangeTheAccountingDate() {
        var value = ExpensePartialAdjustmentTest.adjustment();
        for (var actor : java.util.List.of("alice", "cashier")) invalid(() -> ExpensePartialAdjustmentPreparation.queue(new ExpensePartialAdjustmentPreparation.Input(UUID.randomUUID(), value,
                ExpensePartialAdjustmentPreparation.Side.ACCRUAL, LocalDate.of(2026, 9, 30), actor, "proof", "独立准备", NOW)));
        invalid(() -> ExpensePartialAdjustmentPreparation.queue(new ExpensePartialAdjustmentPreparation.Input(UUID.randomUUID(), value,
                ExpensePartialAdjustmentPreparation.Side.ACCRUAL, LocalDate.of(2026, 9, 29), "finance", "proof", "不可早于原挂账", NOW)));
    }

    private static ExpensePartialAdjustmentPreparation queue(ExpensePartialAdjustment value, ExpensePartialAdjustmentPreparation.Side side, Instant at) {
        return ExpensePartialAdjustmentPreparation.queue(new ExpensePartialAdjustmentPreparation.Input(UUID.randomUUID(), value, side,
                LocalDate.of(2026, 9, 30), "finance", "read-proof", "核对本次独立差额", at));
    }
    private static ExpensePartialAdjustmentPreparation ready(ExpensePartialAdjustment value, ExpensePartialAdjustmentPreparation.Side side, Instant at) {
        var queued = queue(value, side, at); return queued.claim(value.input().basis().funding(), at, LEASE).ready(evidence(queued, at), at);
    }
    private static ExpensePartialAdjustmentPreparation.Evidence evidence(ExpensePartialAdjustmentPreparation value, Instant at) {
        var source = value.input().adjustment().input().basis().funding(); var old = source.payment().observation();
        var observed = new PaymentObservation(old.authorizationId(), old.commandDigest(), old.status(), old.revision(), at, old.paymentReference(), old.paidAmount(), old.accountDigest(), old.completedAt(), old.receiptReference(), old.failure());
        var payment = source.payment().requestQuery(at).claim(at, LEASE).complete(new FinanceResult.Success<>(observed), at);
        var financial = source.financial();
        var refreshed = new ExpenseAdjustmentFinancialSource(financial.change(), financial.settlement(), financial.consumption(), refresh(financial.accrual(), at));
        var funding = new ExpenseAdjustmentFundingSource(refreshed, source.returns(), source.latestRegistration(), payment, refresh(source.paymentVoucher(), at), source.paymentVoucherReversal(), source.previousReturns(), source.selectedReturns());
        var original = source.latestRegistration().receipt();
        var bank = new ExpensePaymentReturnPort.Receipt(original.request(), original.status(), original.revision(), at, at.plusSeconds(300), observed, original.returns());
        var date = value.input().accountingDate();
        var period = new AccountingPeriodPort.OpenPeriod(value.input().periodRequest(), "period", "v1", date, date, at, at.plusSeconds(300));
        return new ExpensePartialAdjustmentPreparation.Evidence(funding, bank, period, at);
    }
    private static VoucherOperation refresh(VoucherOperation old, Instant at) {
        var value = old.observation(); var observation = new VoucherObservation(value.operationId(), value.commandDigest(), value.status(), value.revision(), at,
                value.postingReference(), value.voucherReference(), value.periodReference(), value.accountingDate(), value.debitTotal(), value.creditTotal(), value.postedAt(), value.failure());
        return old.requestQuery(at).claim(at, LEASE).complete(new FinanceResult.Success<>(observation), at);
    }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class); }
}
