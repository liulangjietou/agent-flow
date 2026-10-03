package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.notification.RepaymentReviewNotice;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 原还款复核的租约、来源和明确裁决分别约束，读取不能自动产生决定。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentReviewCheckTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    @Test void expiredLeaseForeignSourceAndTransportFailureCannotProduceUsableEvidence() {
        var check = active(); var evidence = evidence(check, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED);
        assertThat(check.complete(new FinanceResult.Success<>(evidence), check.leaseUntil()).issue()).isEqualTo(AdvanceRepaymentReviewCheck.Issue.TIMEOUT);
        assertThat(check.complete(new FinanceResult.Success<>(evidence(active(), AdvanceRepaymentAdjustmentPort.Status.CONFIRMED)), NOW).issue()).isEqualTo(AdvanceRepaymentReviewCheck.Issue.INVALID_RESPONSE);
        assertThat(check.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TARGET_CHANGED), NOW).issue()).isEqualTo(AdvanceRepaymentReviewCheck.Issue.TARGET_CHANGED);
        assertThat(check.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE), NOW).issue()).isEqualTo(AdvanceRepaymentReviewCheck.Issue.SOURCE_UNAVAILABLE);
        assertThat(check.voidSource(NOW).status()).isEqualTo(AdvanceRepaymentReviewCheck.Status.VOIDED);
    }
    @Test void onlyFreshTerminalEvidenceCanBeConsumedExactlyOnceByOriginalIndependentFinance() {
        var active = active(); var unresolved = active.complete(new FinanceResult.Success<>(evidence(active, AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED)), NOW);
        assertThat(unresolved.usable(NOW)).isFalse(); var check = active.complete(new FinanceResult.Success<>(evidence(active, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED)), NOW);
        assertThat(check.usable(NOW)).isTrue(); assertThat(check.usable(NOW.plusSeconds(300))).isFalse();
        var decision = new AdvanceRepaymentResolution(UUID.randomUUID(), "demo", check.input().id(), check.receipt(), "finance", NOW, "evidence", "确认");
        var resolved = check.resolve(decision, NOW); assertThat(resolved.status()).isEqualTo(AdvanceRepaymentReviewCheck.Status.RESOLVED);
        assertThat(resolved.usable(NOW)).isFalse(); assertThatThrownBy(() -> resolved.resolve(decision, NOW)).isInstanceOf(DomainException.class);
        var other = new AdvanceRepaymentResolution(UUID.randomUUID(), "demo", check.input().id(), check.receipt(), "another-finance", NOW, "evidence", "其他人");
        assertThatThrownBy(() -> check.resolve(other, NOW)).isInstanceOf(DomainException.class);
    }
    @Test void malformedRestoredLeaseDecisionAndBackdatedTransitionsFailClosed() {
        var active = active();
        assertThatThrownBy(() -> active.fail(AdvanceRepaymentReviewCheck.Issue.CONNECTION, NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentReviewCheck(active.input(), 2, AdvanceRepaymentReviewCheck.Status.RUNNING, NOW, null, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentReviewCheck(active.input(), 3, AdvanceRepaymentReviewCheck.Status.RESOLVED, NOW, null, evidence(active, AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED), UUID.randomUUID(), null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentReviewCheck(active.input(), 3, AdvanceRepaymentReviewCheck.Status.UNAVAILABLE, NOW, null, null, null, null, null)).isInstanceOf(DomainException.class);
    }
    @Test void originalComparisonIsImmutableAndPreservedByResolution() {
        var active = active(); var checked = active.complete(new FinanceResult.Success<>(evidence(active, AdvanceRepaymentAdjustmentPort.Status.CONFIRMED)), NOW);
        assertThat(checked.reviewRequired()).isNull(); assertThat(RepaymentReviewNotice.from(checked)).isEmpty();
        var conflict = checked.withReviewRequirement(true);
        assertThat(RepaymentReviewNotice.from(conflict)).contains(RepaymentReviewNotice.REVIEW_REQUIRED);
        assertThatThrownBy(() -> conflict.withReviewRequirement(false)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> active.withReviewRequirement(true)).isInstanceOf(DomainException.class);
        var decision = new AdvanceRepaymentResolution(UUID.randomUUID(), "demo", checked.input().id(), checked.receipt(), "finance", NOW, "proof", "明确裁决");
        var resolved = conflict.resolve(decision, NOW); assertThat(resolved.reviewRequired()).isTrue();
        assertThat(RepaymentReviewNotice.REVIEW_REQUIRED.presentIn(resolved)).isTrue();
        assertThat(RepaymentReviewNotice.from(resolved)).contains(RepaymentReviewNotice.RESOLVED);
        assertThat(RepaymentReviewNotice.REVIEW_REQUIRED.presentIn(checked.resolve(decision, NOW))).isFalse();
        assertThat(RepaymentReviewNotice.from(checked.withReviewRequirement(false))).isEmpty();
    }
    @Test void unavailableUnresolvedAndSourceChangedHaveNoFinancialDecision() {
        var active = active(); var unknown = active.complete(new FinanceResult.Success<>(evidence(active, AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED)), NOW).withReviewRequirement(true);
        assertThat(RepaymentReviewNotice.from(unknown)).contains(RepaymentReviewNotice.UNRESOLVED);
        assertThat(RepaymentReviewNotice.RESOLVED.presentIn(unknown)).isFalse();
        assertThat(RepaymentReviewNotice.from(active.fail(AdvanceRepaymentReviewCheck.Issue.CONNECTION, NOW))).contains(RepaymentReviewNotice.UNAVAILABLE);
        assertThat(RepaymentReviewNotice.from(active.voidSource(NOW))).contains(RepaymentReviewNotice.SOURCE_CHANGED);
    }
    @Test void noticeKeysRejectOtherBusinessesAndNoncanonicalIdentifiers() {
        var id = UUID.fromString("abcdefab-abcd-abcd-abcd-abcdefabcdef");
        for (var notice : RepaymentReviewNotice.values()) assertThat(RepaymentReviewNotice.source(notice.eventKey(id))).contains(new RepaymentReviewNotice.Source(id, notice));
        for (String key : java.util.List.of("repayment:" + id + ":RESOLVED", "repayment-review:1-1-1-1-1:RESOLVED", "repayment-review:" + id + ":CONFIRMED",
                "repayment-review:" + id + ":RESOLVED:2", "repayment-review:" + id.toString().toUpperCase() + ":RESOLVED")) assertThat(RepaymentReviewNotice.source(key)).isEmpty();
        assertThat(RepaymentReviewNotice.source(null)).isEmpty();
    }
    private static AdvanceRepaymentReviewCheck active() {
        var amount = new Money(new BigDecimal("25"), "CNY"); var original = new AdvanceRepaymentPort.Receipt(new AdvanceRepaymentPort.Request(UUID.randomUUID(), UUID.randomUUID(), "alice", "payment", "CNY", "receipt"),
                AdvanceRepaymentPort.Status.CONFIRMED, 1, NOW.minusSeconds(600), NOW.minusSeconds(300), new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.CASH, "cash", amount, NOW.minusSeconds(600)),
                new AdvanceRepaymentPort.Posting("voucher", "credit", amount, LocalDate.parse("2026-09-29"), NOW.minusSeconds(600)));
        return AdvanceRepaymentReviewCheck.queue(new AdvanceRepaymentReviewCheck.Input(UUID.randomUUID(), "demo", "a".repeat(64), new AdvanceRepaymentAdjustmentPort.Request(UUID.randomUUID(), original), "finance", NOW)).claim(NOW, Duration.ofSeconds(90));
    }
    private static AdvanceRepaymentAdjustmentPort.Receipt evidence(AdvanceRepaymentReviewCheck check, AdvanceRepaymentAdjustmentPort.Status status) {
        var original = check.input().request().original(); var current = new AdvanceRepaymentPort.Receipt(original.request(), AdvanceRepaymentPort.Status.CONFIRMED, 2, NOW, NOW.plusSeconds(300), original.funding(), original.posting());
        return new AdvanceRepaymentAdjustmentPort.Receipt(check.input().request(), status, 2, NOW, NOW.plusSeconds(300), current, null, null);
    }
}
