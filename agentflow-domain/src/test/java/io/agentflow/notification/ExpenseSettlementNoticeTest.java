package io.agentflow.notification;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.expense.ExpenseSettlement;
import io.agentflow.finance.Money;
import io.agentflow.finance.VoucherPreparation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 等待状态不冒充完成，再次阻塞和历史修订有不同通知身份。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSettlementNoticeTest {
    @Test void waitingIsQuietAndPersistedProblemsKeepTheirOriginalRevision() {
        var now = Instant.parse("2026-10-03T12:00:00Z"); var id = UUID.randomUUID();
        var binding = new VoucherPreparation.Source("demo", BusinessReference.Type.EXPENSE, id, UUID.randomUUID(), 1, 3, 2, "alice");
        var zero = new Money(BigDecimal.ZERO, "CNY"); var queued = ExpenseSettlement.queue(new ExpenseSettlement.Input(binding, zero, zero, null, null, null, now), now);
        assertThat(ExpenseSettlementNotice.from(queued)).isEmpty();
        var blocked = queued.block("INVOICE_VERIFICATION_REQUIRED", now);
        assertThat(ExpenseSettlementNotice.from(blocked)).contains(ExpenseSettlementNotice.BLOCKED);
        var repeated = blocked.retry(now).block("INVOICE_VERIFICATION_REQUIRED", now);
        assertThat(ExpenseSettlementNotice.BLOCKED.eventKey(id, blocked.version())).isNotEqualTo(ExpenseSettlementNotice.BLOCKED.eventKey(id, repeated.version()));
        var budget = UUID.randomUUID(); var pending = queued.consumed(budget, now); assertThat(ExpenseSettlementNotice.from(pending)).isEmpty();
        assertThat(ExpenseSettlementNotice.from(pending.budgetResolved(budget, false, "BUDGET_ACCOUNTING_PERIOD_CLOSED", now))).contains(ExpenseSettlementNotice.BUDGET_REJECTED);
        var settled = pending.budgetResolved(budget, true, null, now); assertThat(ExpenseSettlementNotice.from(settled)).contains(ExpenseSettlementNotice.SETTLED);
        assertThat(ExpenseSettlementNotice.from(settled.requireReview("EXPENSE_PAYMENT_REVIEW", now))).contains(ExpenseSettlementNotice.REVIEW_REQUIRED);
        assertThat(ExpenseSettlementNotice.SETTLED.kind()).isEqualTo(InboxMessage.Kind.EXPENSE_SETTLEMENT_RESULT);
    }
    @Test void sourceRejectsNoncanonicalRevisionAndUnrelatedFacts() {
        var id = UUID.randomUUID(); String key = ExpenseSettlementNotice.BLOCKED.eventKey(id, 2);
        assertThat(ExpenseSettlementNotice.source(key)).contains(new ExpenseSettlementNotice.Source(id, 2, ExpenseSettlementNotice.BLOCKED));
        for (String bad : new String[]{"expense-settlement:1-1-1-1-1:2:BLOCKED", "expense-settlement:"+id+":02:BLOCKED", "expense-settlement:"+id+":0:BLOCKED",
                "expense-settlement:"+id+":9223372036854775808:BLOCKED", "expense-settlement:"+id+":2:QUEUED", key+":extra", "budget:"+id+":2:BLOCKED"}) {
            assertThat(ExpenseSettlementNotice.source(bad)).isEmpty();
        }
    }
}
