package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 预算领域测试的合成台账，不作为默认企业额度或生产适配器。
 * @author owlzhangfq@gmail.com
 */
final class BudgetAdjustmentTestSupport {
    static final UUID ENTITY = UUID.fromString("285d8c73-bb1a-4414-b074-5383749aab9b");
    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final LocalDate DATE = LocalDate.parse("2026-09-29");
    static final String TARGET = "a".repeat(64);

    private BudgetAdjustmentTestSupport() { }

    static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    static BudgetAdjustmentContent content(BudgetAdjustmentContent.Type type, String amount) {
        return new BudgetAdjustmentContent(ENTITY, "预算调整", "业务预算变更", type, DATE,
                type == BudgetAdjustmentContent.Type.INCREASE ? null : "budget-source",
                type == BudgetAdjustmentContent.Type.DECREASE ? null : "budget-target", money(amount));
    }
    static BudgetLedgerPort.Position position(String reference, String limit, String committed, String consumed) {
        return new BudgetLedgerPort.Position(ENTITY, reference, "年度预算", "v1", "2026", DATE.withDayOfYear(1),
                LocalDate.parse("2026-12-31"), BudgetLedgerPort.PeriodStatus.OPEN, money(limit), money(committed), money(consumed));
    }
    static BudgetLedgerPort.Snapshot ledger(BudgetAdjustmentContent content, BudgetLedgerPort.Position... positions) {
        return new BudgetLedgerPort.Snapshot(content.ledgerRequest("alice"), "ledger-v1", NOW.minusSeconds(1), NOW.plusSeconds(300), List.of(positions));
    }
    static BudgetLedgerPort.Snapshot ledger(BudgetAdjustmentContent content) {
        return ledger(content, content.ledgerRequest("alice").budgetReferences().stream()
                .map(reference -> position(reference, "1000", "300", "450")).toArray(BudgetLedgerPort.Position[]::new));
    }
    static FinanceCatalog catalog() {
        return new FinanceCatalog("alice", "v1", NOW.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(ENTITY,
                "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
    }
    static InitiatorContext initiator() {
        return new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
    }
    static BudgetAdjustmentRequest draft(BudgetAdjustmentContent content) {
        return BudgetAdjustmentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
    }
    static void freeze(BudgetAdjustmentRequest request, long expectedVersion, int round) {
        request.freeze(expectedVersion, round, catalog(), TARGET, ledger(request.content()), initiator(), NOW);
    }
    static BudgetAdjustmentRequest approved() {
        var request = draft(content(BudgetAdjustmentContent.Type.TRANSFER, "70"));
        freeze(request, 1, 1); request.approve(2, 1, 4, "manager", NOW); return request;
    }
    static BudgetLedgerPort.Snapshot currentLedger(ApprovedBudgetAdjustment source) {
        var ledger = source.round().ledger();
        return new BudgetLedgerPort.Snapshot(ledger.request(), "ledger-v2", NOW.plusSeconds(1), NOW.plusSeconds(301), ledger.positions());
    }
    static BudgetAdjustmentCommand command() {
        var source = ApprovedBudgetAdjustment.from(approved());
        return BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, currentLedger(source), "finance", "复核原预算调整", NOW.plusSeconds(2));
    }
    static BudgetAdjustmentObservation applied(BudgetAdjustmentCommand command, long revision, Instant observedAt) {
        var changes = command.changes().stream().map(change -> {
            var position = command.ledger().position(change.budgetReference());
            return new BudgetAdjustmentObservation.AppliedChange(change.budgetReference(), change.expectedVersion(), "applied-v2", position.periodReference(),
                    command.source().round().content().accountingDate(), change.beforeLimit(), change.afterLimit(), position.committed(), position.consumed());
        }).toList();
        return new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.APPLIED, revision,
                observedAt, "budget-adjustment-1", command.authorizedAt(), changes, null);
    }
    static void fails(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
