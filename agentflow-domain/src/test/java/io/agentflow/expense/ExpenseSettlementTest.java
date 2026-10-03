package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.VoucherPreparation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 资金成功、资源核销和预算实际占用分别确认，重试与争议不能把它们混为一个成功标记。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSettlementTest {
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test void budgetRetryRetainsCompletedResourceConsumptionAndAcceptsOnlyItsOriginalOperation() {
        var queued = ExpenseSettlement.queue(input("100", "30"), NOW); var budget = UUID.randomUUID();
        assertThat(queued.resourcesConsumed()).isFalse();
        var pending = queued.consumed(budget, NOW.plusSeconds(1));
        assertThat(pending.status()).isEqualTo(ExpenseSettlement.Status.BUDGET_PENDING);
        assertThatThrownBy(() -> pending.budgetResolved(UUID.randomUUID(), true, null, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var rejected = pending.budgetResolved(budget, false, "BUDGET_REJECTED", NOW.plusSeconds(2));
        var retry = rejected.retry(NOW.plusSeconds(3));
        assertThat(retry.resourcesConsumed()).isTrue(); assertThat(retry.budgetOperationId()).isNull();
        var nextId = UUID.randomUUID(); var next = retry.consumed(nextId, NOW.plusSeconds(4));
        var settled = next.budgetResolved(nextId, true, null, NOW.plusSeconds(5));
        assertThat(settled.status()).isEqualTo(ExpenseSettlement.Status.SETTLED);
        assertThatThrownBy(() -> settled.retry(NOW.plusSeconds(6))).isInstanceOf(DomainException.class);
        assertThat(settled.input()).isEqualTo(queued.input());
    }

    @Test void sourceBlockingDoesNotClaimResourcesWereConsumedAndReviewCannotBeClearedByLateBudget() {
        var queued = ExpenseSettlement.queue(input("100", "30"), NOW);
        var blocked = queued.block("INVOICE_VERIFICATION_REQUIRED", NOW.plusSeconds(1));
        assertThat(blocked.resourcesConsumed()).isFalse(); assertThat(blocked.retry(NOW.plusSeconds(2)).resourcesConsumed()).isFalse();
        var id = UUID.randomUUID(); var pending = queued.consumed(id, NOW.plusSeconds(1));
        var disputed = pending.requireReview("PAYMENT_REVERSED", NOW.plusSeconds(2));
        assertThat(disputed.resourcesConsumed()).isTrue(); assertThat(disputed.budgetResolved(id, true, null, NOW.plusSeconds(3))).isEqualTo(disputed);
        assertThatThrownBy(() -> disputed.retry(NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
    }

    @Test void fullOffsetAndFullReductionHaveNoSyntheticZeroPaymentOrVoucher() {
        var offset = input("100", "100"); assertThat(offset.payment()).isNull(); assertThat(offset.voucherOperationId()).isNotNull();
        var reduced = input("0", "0"); assertThat(reduced.payment()).isNull(); assertThat(reduced.voucherOperationId()).isNull();
        assertThatThrownBy(() -> new ExpenseSettlement.Input(offset.source(), money("100"), money("100"), offset.voucherOperationId(), DIGEST,
                input("100", "0").payment(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ExpenseSettlement.Input(offset.source(), money("100"), money("0"), null, null, input("100", "0").payment(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExpenseSettlement.queue(offset, NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
    }

    private ExpenseSettlement.Input input(String gross, String offsets) {
        var source = new VoucherPreparation.Source("demo", BusinessReference.Type.EXPENSE, UUID.randomUUID(), UUID.randomUUID(), 1, 5, 2, "alice");
        var payable = money(gross).minus(money(offsets));
        var payment = payable.value().signum() == 0 ? null : new ExpenseSettlement.Payment(UUID.randomUUID(), DIGEST, payable, "original-bank", "original-receipt", NOW);
        return new ExpenseSettlement.Input(source, money(gross), money(offsets), money(gross).value().signum() == 0 ? null : UUID.randomUUID(), money(gross).value().signum() == 0 ? null : DIGEST, payment, NOW);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
