package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 查询租约和凭据有效期各自约束迟到结果，确认不能更换原财务或事实。
 * @author owlzhangfq@gmail.com
 */
class AdvanceRepaymentCheckTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    @Test void timeoutAndForeignEvidenceNeverBecomeUsable() {
        var active = active(); var receipt = receipt(active, AdvanceRepaymentPort.Status.CONFIRMED);
        var late = active.complete(new FinanceResult.Success<>(receipt), active.leaseUntil());
        assertThat(late.status()).isEqualTo(AdvanceRepaymentCheck.Status.UNAVAILABLE); assertThat(late.issue()).isEqualTo(AdvanceRepaymentCheck.Issue.TIMEOUT);
        assertThat(active.complete(new FinanceResult.Success<>(receipt(active(), AdvanceRepaymentPort.Status.CONFIRMED)), NOW).issue()).isEqualTo(AdvanceRepaymentCheck.Issue.INVALID_RESPONSE);
        assertThat(active.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.CONNECTION), NOW).issue()).isEqualTo(AdvanceRepaymentCheck.Issue.CONNECTION);
        assertThat(active.complete(new FinanceResult.Rejected<>(FinanceResult.Reason.EMPLOYEE_UNAVAILABLE), NOW).issue()).isEqualTo(AdvanceRepaymentCheck.Issue.SOURCE_UNAVAILABLE);
    }
    @Test void onlyConfirmedUnexpiredEvidenceCanBeConsumedOnceByOriginalFinance() {
        var active = active();
        for (var status : AdvanceRepaymentPort.Status.values()) {
            var completed = active.complete(new FinanceResult.Success<>(receipt(active, status)), NOW);
            assertThat(completed.usable(NOW)).isEqualTo(status == AdvanceRepaymentPort.Status.CONFIRMED);
            assertThat(completed.usable(NOW.plusSeconds(300))).isFalse();
        }
        var checked = active.complete(new FinanceResult.Success<>(receipt(active, AdvanceRepaymentPort.Status.CONFIRMED)), NOW);
        var original = new AdvanceRepayment(UUID.randomUUID(), "demo", active.input().id(), checked.receipt(), "finance", NOW, "确认");
        var recorded = checked.record(original, NOW); assertThat(recorded.status()).isEqualTo(AdvanceRepaymentCheck.Status.RECORDED); assertThat(recorded.usable(NOW)).isFalse();
        assertThatThrownBy(() -> recorded.record(original, NOW)).isInstanceOf(DomainException.class);
        var foreignActor = new AdvanceRepayment(UUID.randomUUID(), "demo", active.input().id(), checked.receipt(), "another-finance", NOW, "不能代替原确认人");
        assertThatThrownBy(() -> checked.record(foreignActor, NOW)).isInstanceOf(DomainException.class);
    }
    @Test void invalidRestoredStatesAndBackdatedTransitionsFailClosed() {
        var active = active();
        assertThatThrownBy(() -> active.complete(new FinanceResult.Success<>(receipt(active, AdvanceRepaymentPort.Status.CONFIRMED)), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentCheck(active.input(), 2, AdvanceRepaymentCheck.Status.RUNNING, NOW, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentCheck(active.input(), 2, AdvanceRepaymentCheck.Status.UNAVAILABLE, NOW, null, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new AdvanceRepaymentCheck(active.input(), 2, AdvanceRepaymentCheck.Status.RECORDED, NOW, null, receipt(active, AdvanceRepaymentPort.Status.PENDING), UUID.randomUUID(), null)).isInstanceOf(DomainException.class);
        assertThat(active.voidSource(NOW).issue()).isEqualTo(AdvanceRepaymentCheck.Issue.SOURCE_CHANGED);
    }
    private static AdvanceRepaymentCheck active() {
        return AdvanceRepaymentCheck.queue(new AdvanceRepaymentCheck.Input(UUID.randomUUID(), "demo", UUID.randomUUID(), 4, "a".repeat(64),
                new AdvanceRepaymentPort.Request(UUID.randomUUID(), UUID.randomUUID(), "alice", "original-payment", "CNY", "receipt-1"), "finance", NOW)).claim(NOW, Duration.ofSeconds(90));
    }
    private static AdvanceRepaymentPort.Receipt receipt(AdvanceRepaymentCheck check, AdvanceRepaymentPort.Status status) {
        boolean terminal = status == AdvanceRepaymentPort.Status.CONFIRMED || status == AdvanceRepaymentPort.Status.REVERSED;
        var amount = new Money(new BigDecimal("25"), "CNY");
        return new AdvanceRepaymentPort.Receipt(check.input().request(), status, status == AdvanceRepaymentPort.Status.NOT_FOUND ? 0 : 1, NOW, NOW.plusSeconds(300),
                terminal ? new AdvanceRepaymentPort.Funding(AdvanceRepaymentPort.Channel.BANK_TRANSFER, "bank-original", amount, NOW) : null,
                terminal ? new AdvanceRepaymentPort.Posting("erp-original", "entry-original", amount, LocalDate.parse("2026-09-29"), NOW) : null);
    }
}
