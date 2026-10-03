package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实退回必须有独立资金和借方分录，原收款撤销本身不能改变借款余额。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentAdjustmentPortTest {
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final Money AMOUNT = new Money(new BigDecimal("100.00"), "CNY");
    private static final LocalDate DATE = LocalDate.parse("2026-09-29");

    @Test void originalHistoricalReceiptAndFreshConfirmationHaveDifferentEvidenceLifetimes() {
        var request = request(); var current = current(request, AdvanceRepaymentPort.Status.CONFIRMED);
        assertThat(request.original().matches(request.original().request(), NOW)).isFalse();
        var result = receipt(request, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED, current, null, null);
        assertThat(result.matches(request, NOW)).isTrue(); assertThat(result.matches(request, NOW.plusSeconds(300))).isFalse();
        assertThat(result.matches(request, NOW.minusNanos(1))).isFalse(); assertThat(result.matches(request(), NOW)).isFalse();
    }

    @Test void completeIndependentReturnProofRetainsOriginalReceiptAndAllowsOnlyWholeAmount() {
        var request = request(); var current = current(request, AdvanceRepaymentPort.Status.REVERSED);
        var result = receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), posting());
        assertThat(result.matches(request, NOW)).isTrue(); assertThat(result.sameReturn(result)).isTrue();
        var partial = new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "return-1", new Money(new BigDecimal("99"), "CNY"), NOW.minusSeconds(2));
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, partial, posting())).isInstanceOf(DomainException.class);
        var changed = new AdvanceRepaymentAdjustmentPort.ReturnPosting("other-voucher", "debit-1", AMOUNT, DATE, NOW.minusSeconds(1));
        assertThat(result.sameReturn(receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), changed))).isFalse();
    }

    @Test void reversalAloneOrOriginalReceiptEntryCannotStandForRealReturnedFundsAndDebit() {
        var request = request(); var current = current(request, AdvanceRepaymentPort.Status.REVERSED);
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, null, posting())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), null)).isInstanceOf(DomainException.class);
        var oldFunds = new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "received-1", AMOUNT, NOW.minusSeconds(2));
        var oldPosting = new AdvanceRepaymentAdjustmentPort.ReturnPosting("original-voucher", "credit-1", AMOUNT, DATE, NOW.minusSeconds(1));
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, oldFunds, posting())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), oldPosting)).isInstanceOf(DomainException.class);
    }

    @Test void currentFactsMustRetainExactOriginalSettlementAndNondecreasingSourceRevision() {
        var request = request(); var current = current(request, AdvanceRepaymentPort.Status.REVERSED);
        var changed = new AdvanceRepaymentPort.Receipt(current.request(), current.status(), 2, NOW, NOW.plusSeconds(300),
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "different-funds", AMOUNT, NOW.minusSeconds(7200)), current.posting());
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, changed, funds(), posting())).isInstanceOf(DomainException.class);
        var stale = new AdvanceRepaymentPort.Receipt(current.request(), current.status(), 1, NOW, NOW.plusSeconds(300), current.funding(), current.posting());
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, stale, funds(), posting())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED, current, null, null)).isInstanceOf(DomainException.class);
    }

    @Test void combinedReceiptCannotExtendSourceExpiryOrAcceptFutureOrUnpostedReturn() {
        var request = request(); var current = current(request, AdvanceRepaymentPort.Status.REVERSED);
        var shorter = new AdvanceRepaymentPort.Receipt(current.request(), current.status(), 3, NOW, NOW.plusSeconds(30), current.funding(), current.posting());
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, shorter, funds(), posting())).isInstanceOf(DomainException.class);
        var tooEarly = new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-voucher", "debit-1", AMOUNT, DATE, NOW.minusSeconds(3));
        var future = new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-voucher", "debit-1", AMOUNT, DATE, NOW.plusSeconds(1));
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), tooEarly)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, current, funds(), future)).isInstanceOf(DomainException.class);
    }

    @Test void unresolvedLookupCarriesNoFinancialAdjustmentAndCannotInventOriginalReceipt() {
        var request = request(); var unknown = receipt(request, AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED, null, null, null);
        assertThat(unknown.matches(request, NOW)).isTrue();
        assertThatThrownBy(() -> receipt(request, AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED, null, funds(), posting())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentAdjustmentPort.Request(UUID.randomUUID(), current(request, AdvanceRepaymentPort.Status.REVERSED))).isInstanceOf(DomainException.class);
    }

    @Test void cumulativePartialReturnsKeepIndependentProofsAndOnlyFullReturnReversesOriginal() {
        var request = request(); var first = item("first", "30"); var second = item("second", "20");
        var partial = returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(first));
        var later = returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(first, second));
        assertThat(later.totalReturned()).isEqualTo(new Money(new BigDecimal("50"), "CNY"));
        assertThat(later.preservesReturns(partial)).isTrue(); assertThat(partial.preservesReturns(later)).isFalse(); assertThat(later.sameReturn(partial)).isFalse();
        assertThat(later.sameReturn(returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(second, first)))).isTrue();
        var full = returned(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, List.of(first, second, item("last", "50")));
        assertThat(full.preservesReturns(later)).isTrue(); assertThat(full.totalReturned()).isEqualTo(AMOUNT);
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.RETURNED, List.of(first))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(item("whole", "100")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(item("large", "101")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentAdjustmentPort.Receipt(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, 3, NOW, NOW.plusSeconds(300),
                current(request, AdvanceRepaymentPort.Status.REVERSED), first.fundsReturn(), first.posting())).isInstanceOf(DomainException.class);
    }

    @Test void duplicateFundsOrDebitEntriesCannotInflateCumulativeReturn() {
        var request = request(); var first = item("first", "30"); var second = item("second", "30");
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED, List.of(first, first))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED,
                List.of(first, new AdvanceRepaymentAdjustmentPort.ReturnItem(first.fundsReturn(), second.posting())))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> returned(request, AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED,
                List.of(first, new AdvanceRepaymentAdjustmentPort.ReturnItem(second.fundsReturn(), first.posting())))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentAdjustmentPort.Receipt(request, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED, 3, NOW, NOW.plusSeconds(300),
                current(request, AdvanceRepaymentPort.Status.CONFIRMED), null, null, List.of(first))).isInstanceOf(DomainException.class);
    }

    private static AdvanceRepaymentAdjustmentPort.Receipt returned(AdvanceRepaymentAdjustmentPort.Request request, AdvanceRepaymentAdjustmentPort.Status status, List<AdvanceRepaymentAdjustmentPort.ReturnItem> entries) {
        return new AdvanceRepaymentAdjustmentPort.Receipt(request, status, 3, NOW, NOW.plusSeconds(300),
                current(request, status == AdvanceRepaymentAdjustmentPort.Status.RETURNED ? AdvanceRepaymentPort.Status.REVERSED : AdvanceRepaymentPort.Status.CONFIRMED),
                entries.get(0).fundsReturn(), entries.get(0).posting(), entries.subList(1, entries.size()));
    }
    private static AdvanceRepaymentAdjustmentPort.ReturnItem item(String reference, String value) {
        var amount = new Money(new BigDecimal(value), "CNY");
        return new AdvanceRepaymentAdjustmentPort.ReturnItem(new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.CASH, reference, amount, NOW.minusSeconds(2)),
                new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-" + reference, "debit-1", amount, DATE, NOW.minusSeconds(1)));
    }

    private static AdvanceRepaymentAdjustmentPort.Request request() {
        var request = new AdvanceRepaymentPort.Request(UUID.randomUUID(), UUID.randomUUID(), "alice", "loan-original", "CNY", "receipt-original");
        var original = new AdvanceRepaymentPort.Receipt(request, AdvanceRepaymentPort.Status.CONFIRMED, 2, NOW.minusSeconds(3600), NOW.minusSeconds(3300),
                new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "received-1", AMOUNT, NOW.minusSeconds(7200)),
                new AdvanceRepaymentPort.Posting("original-voucher", "credit-1", AMOUNT, DATE, NOW.minusSeconds(4000)));
        return new AdvanceRepaymentAdjustmentPort.Request(UUID.randomUUID(), original);
    }
    private static AdvanceRepaymentPort.Receipt current(AdvanceRepaymentAdjustmentPort.Request request, AdvanceRepaymentPort.Status status) {
        return new AdvanceRepaymentPort.Receipt(request.original().request(), status, 3, NOW, NOW.plusSeconds(300), request.original().funding(), request.original().posting());
    }
    private static AdvanceRepaymentAdjustmentPort.Receipt receipt(AdvanceRepaymentAdjustmentPort.Request request, AdvanceRepaymentAdjustmentPort.Status status,
            AdvanceRepaymentPort.Receipt current, AdvanceRepaymentAdjustmentPort.FundsReturn funds, AdvanceRepaymentAdjustmentPort.ReturnPosting posting) {
        return new AdvanceRepaymentAdjustmentPort.Receipt(request, status, 1, NOW, NOW.plusSeconds(300), current, funds, posting);
    }
    private static AdvanceRepaymentAdjustmentPort.FundsReturn funds() { return new AdvanceRepaymentAdjustmentPort.FundsReturn(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "return-1", AMOUNT, NOW.minusSeconds(2)); }
    private static AdvanceRepaymentAdjustmentPort.ReturnPosting posting() { return new AdvanceRepaymentAdjustmentPort.ReturnPosting("return-voucher", "debit-1", AMOUNT, DATE, NOW.minusSeconds(1)); }
}
