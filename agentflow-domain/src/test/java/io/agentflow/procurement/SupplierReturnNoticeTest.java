package io.agentflow.procurement;

import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentObservation;
import io.agentflow.notification.SupplierReturnNotice;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPayableSettlementTest.bank;
import static org.assertj.core.api.Assertions.*;

/**
 * 回款查询、候选与实际登记保持独立，历史消息不能把待确认解释成已登记。
 * @author owlzhangfq@gmail.com
 */
class SupplierReturnNoticeTest {
    @Test void queuedRunningAndConfirmedQueriesRemainQuietWhileFailuresHaveNoMoneyConclusion() {
        var bank = bank(); var at = bank.updatedAt().plusSeconds(1); var request = new SupplierPaymentReturnPort.Request(bank.command(), bank.observation());
        var queued = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), bank.command().tenantId(), bank.command().targetDigest(), bank.version(), request, "finance", at));
        var active = queued.claim(at, Duration.ofSeconds(30));
        assertThat(SupplierReturnNotice.from(queued)).isEmpty(); assertThat(SupplierReturnNotice.from(active)).isEmpty();
        assertThat(SupplierReturnNotice.from(active.fail(SupplierPaymentReturnCheck.Issue.TIMEOUT, active.leaseUntil()))).contains(SupplierReturnNotice.UNAVAILABLE);
        assertThat(SupplierReturnNotice.from(queued.voidSource(at))).contains(SupplierReturnNotice.SOURCE_CHANGED);
        var observed = new SupplierPaymentReturnPort.Receipt(request, SupplierPaymentReturnPort.Status.CONFIRMED, 1, at, at.plusSeconds(120), bank.observation(), List.of());
        var checked = active.complete(new FinanceResult.Success<>(observed), at); assertThat(SupplierReturnNotice.from(checked)).isEmpty();
        var decision = new SupplierPaymentReturn(UUID.randomUUID(), bank.command().tenantId(), queued.input().id(), observed, "finance", at, "evidence", "确认原件未见退回");
        var resolved = checked.resolve(decision, at); assertThat(SupplierReturnNotice.from(resolved)).contains(SupplierReturnNotice.RECORDED);
        assertThat(SupplierReturnNotice.RETURN_REVIEW.presentIn(resolved)).isFalse();
    }
    @Test void partialAndFullCandidatesRemainReadableAfterTheirOwnRegistration() {
        var bank = bank(); var at = bank.updatedAt().plusSeconds(1); var request = new SupplierPaymentReturnPort.Request(bank.command(), bank.observation());
        for (var outcome : List.of(SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED, SupplierPaymentReturnPort.Status.RETURNED)) {
            var active = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), bank.command().tenantId(), bank.command().targetDigest(), bank.version(), request, "finance", at)).claim(at, Duration.ofSeconds(30));
            var original = bank.observation(); var current = outcome == SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED ? original
                    : new PaymentObservation(bank.command().id(), bank.command().digest(), PaymentObservation.Status.REVERSED, original.revision() + 1, at, original.paymentReference(), original.paidAmount(), original.accountDigest(), at, "returned", null);
            var funds = new SupplierPaymentReturnPort.BankReceipt("return-one", bank.command().debitAccount().reference(), outcome == SupplierPaymentReturnPort.Status.RETURNED ? bank.command().amount() : money("20"), at);
            var receipt = new SupplierPaymentReturnPort.Receipt(request, outcome, 1, at, at.plusSeconds(120), current, List.of(funds));
            var checked = active.complete(new FinanceResult.Success<>(receipt), at);
            assertThat(SupplierReturnNotice.from(checked)).contains(SupplierReturnNotice.RETURN_REVIEW);
            assertThat(SupplierReturnNotice.RECORDED.presentIn(checked)).isFalse();
            var decision = new SupplierPaymentReturn(UUID.randomUUID(), bank.command().tenantId(), active.input().id(), receipt, "finance", at, "evidence", "明确登记本次回款");
            var resolved = checked.resolve(decision, at); assertThat(SupplierReturnNotice.RETURN_REVIEW.presentIn(resolved)).isTrue();
            assertThat(SupplierReturnNotice.from(resolved)).contains(SupplierReturnNotice.RECORDED);
        }
    }
    @Test void unresolvedEvidenceIsOnlyAttentionAndCannotBeRecorded() {
        var bank = bank(); var at = bank.updatedAt().plusSeconds(1); var request = new SupplierPaymentReturnPort.Request(bank.command(), bank.observation());
        var active = SupplierPaymentReturnCheck.queue(new SupplierPaymentReturnCheck.Input(UUID.randomUUID(), bank.command().tenantId(), bank.command().targetDigest(), bank.version(), request, "finance", at)).claim(at, Duration.ofSeconds(30));
        var receipt = new SupplierPaymentReturnPort.Receipt(request, SupplierPaymentReturnPort.Status.UNRESOLVED, 1, at, at.plusSeconds(120), null, List.of());
        var checked = active.complete(new FinanceResult.Success<>(receipt), at);
        assertThat(SupplierReturnNotice.from(checked)).contains(SupplierReturnNotice.UNRESOLVED); assertThat(SupplierReturnNotice.RECORDED.presentIn(checked)).isFalse();
        assertThat(SupplierReturnNotice.RETURN_REVIEW.presentIn(checked)).isFalse();
    }
    @Test void notificationKeysRequireCanonicalOriginalCheckAndKnownFact() {
        var id = UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef");
        for (var notice : SupplierReturnNotice.values()) {
            assertThat(SupplierReturnNotice.source(notice.eventKey(id))).contains(new SupplierReturnNotice.Source(id, notice));
            assertThat(notice.eventKey(id)).isNotEqualTo(notice.eventKey(UUID.randomUUID()));
        }
        for (var key : List.of("", "supplier-return:1-1-1-1-1:RECORDED", "supplier-return:" + id + ":CONFIRMED", "supplier-return:" + id + ":RECORDED:2", "supplier-return:" + id.toString().toUpperCase() + ":RECORDED")) assertThat(SupplierReturnNotice.source(key)).isEmpty();
        assertThat(SupplierReturnNotice.source(null)).isEmpty();
    }
}
