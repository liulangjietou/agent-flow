package io.agentflow.budget;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.budget.BudgetAdjustmentTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 明确批准与新台账共同约束指令，调拨只有两端完整确认才能成为成功。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentCommandTest {
    @Test void approvedSourceCannotBeDraftOrInventAnotherVersionAndRound() {
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> ApprovedBudgetAdjustment.from(draft(content(BudgetAdjustmentContent.Type.INCREASE, "1"))));
        var request = approved(); var source = ApprovedBudgetAdjustment.from(request);
        assertThat(source.round()).isEqualTo(request.currentRound()); assertThat(source.approvedRequestVersion()).isEqualTo(3);
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> new ApprovedBudgetAdjustment(source.tenantId(), source.requestId(), source.applicationId(),
                "someone-else", source.approvedRequestVersion(), source.round(), source.approval()));
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> new ApprovedBudgetAdjustment(source.tenantId(), source.requestId(), source.applicationId(),
                source.employeeId(), 4, source.round(), source.approval()));
    }

    @Test void authorizationUsesCurrentBalancesWithoutChangingApprovedIntent() {
        var source = ApprovedBudgetAdjustment.from(approved()); var original = source.round().ledger();
        var positions = List.of(position("budget-source", "1200", "400", "450"), position("budget-target", "900", "200", "100"));
        var ledger = new BudgetLedgerPort.Snapshot(original.request(), "ledger-v2", NOW.plusSeconds(1), NOW.plusSeconds(250), positions);
        var command = BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, ledger, "finance", "明确复核最新额度", NOW.plusSeconds(2));
        assertThat(command.changes()).extracting(change -> change.afterLimit().value().toPlainString()).containsExactly("1130.00", "970.00");
        assertThat(source.round().ledger()).isEqualTo(original);
        assertThat(command.expiresAt()).isEqualTo(NOW.plusSeconds(250));
        assertThat(command.targetDigest()).isEqualTo(TARGET);
        assertThat(command.changes()).allSatisfy(change -> assertThat(change.beforeLimit().currency()).isEqualTo("CNY"));
    }

    @Test void freshPostApprovalLedgerAndIndependentNamedActorAreRequired() {
        var source = ApprovedBudgetAdjustment.from(approved()); var ledger = currentLedger(source);
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, source.round().ledger(), "finance", "reason", NOW.plusSeconds(2)));
        for (String actor : List.of("alice", " finance", "finance\n", "")) {
            fails("INVALID_BUDGET_ADJUSTMENT_COMMAND", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, ledger, actor, "reason", NOW.plusSeconds(2)));
        }
        var command = command();
        fails("INVALID_BUDGET_ADJUSTMENT_COMMAND", () -> new BudgetAdjustmentCommand(command.id(), source, ledger, command.changes(), "finance", "reason", NOW.plusSeconds(2), NOW.plusSeconds(302)));
        fails("BUDGET_ADJUSTMENT_AUTHORIZATION_EXPIRED", () -> command.requireSendAt(command.expiresAt()));
        fails("BUDGET_ADJUSTMENT_AUTHORIZATION_EXPIRED", () -> command.requireSendAt(command.authorizedAt().minusNanos(1)));
        assertThatNoException().isThrownBy(() -> command.requireSendAt(command.expiresAt().minusNanos(1)));
    }

    @Test void changedPeriodScopeAndInsufficientCurrentBalanceCannotBeAuthorized() {
        var source = ApprovedBudgetAdjustment.from(approved()); var original = currentLedger(source); var position = original.positions().get(0);
        var renamed = new BudgetLedgerPort.Position(ENTITY, position.reference(), position.name(), position.version(), "OTHER-PERIOD", position.periodStart(),
                position.periodEnd(), position.periodStatus(), position.limit(), position.committed(), position.consumed());
        var wrongPeriod = new BudgetLedgerPort.Snapshot(original.request(), original.sourceVersion(), original.observedAt(), original.validUntil(), List.of(renamed, original.positions().get(1)));
        fails("BUDGET_TRANSFER_PERIOD_MISMATCH", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, wrongPeriod, "finance", "reason", NOW.plusSeconds(2)));
        var movedPositions = original.positions().stream().map(value -> new BudgetLedgerPort.Position(value.legalEntityId(), value.reference(), value.name(),
                value.version(), "OTHER-PERIOD", value.periodStart(), value.periodEnd(), value.periodStatus(), value.limit(), value.committed(), value.consumed())).toList();
        var movedPeriod = new BudgetLedgerPort.Snapshot(original.request(), original.sourceVersion(), original.observedAt(), original.validUntil(), movedPositions);
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, movedPeriod, "finance", "reason", NOW.plusSeconds(2)));
        var spent = new BudgetLedgerPort.Snapshot(original.request(), original.sourceVersion(), original.observedAt(), original.validUntil(),
                List.of(position("budget-source", "1000", "900", "50"), original.positions().get(1)));
        fails("BUDGET_ADJUSTMENT_INSUFFICIENT", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, spent, "finance", "reason", NOW.plusSeconds(2)));
        var otherEmployee = new BudgetLedgerPort.Snapshot(new BudgetLedgerPort.Request(ENTITY, "bob", DATE, original.request().budgetReferences()),
                original.sourceVersion(), original.observedAt(), original.validUntil(), original.positions());
        fails("BUDGET_ADJUSTMENT_SOURCE_CHANGED", () -> BudgetAdjustmentCommand.authorize(UUID.randomUUID(), source, otherEmployee, "finance", "reason", NOW.plusSeconds(2)));
    }

    @Test void commandCannotForgeOneSideOrItsExpectedVersion() {
        var command = command(); var changes = new ArrayList<>(command.changes()); var first = changes.get(0);
        changes.set(0, new BudgetAdjustmentContent.Change(first.budgetReference(), "wrong-version", first.beforeLimit(), first.afterLimit()));
        fails("INVALID_BUDGET_ADJUSTMENT_COMMAND", () -> new BudgetAdjustmentCommand(command.id(), command.source(), command.ledger(), changes,
                command.authorizedBy(), command.reason(), command.authorizedAt(), command.expiresAt()));
        fails("INVALID_BUDGET_ADJUSTMENT_COMMAND", () -> new BudgetAdjustmentCommand(command.id(), command.source(), command.ledger(), List.of(first),
                command.authorizedBy(), command.reason(), command.authorizedAt(), command.expiresAt()));
        assertThatThrownBy(() -> command.changes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void digestBindsWholeDecisionAndLedgerRegardlessOfResponsePositionOrder() {
        var command = command(); var original = command.ledger(); var reversed = new ArrayList<>(original.positions()); Collections.reverse(reversed);
        var reordered = new BudgetLedgerPort.Snapshot(original.request(), original.sourceVersion(), original.observedAt(), original.validUntil(), reversed);
        var same = new BudgetAdjustmentCommand(command.id(), command.source(), reordered, command.changes(), command.authorizedBy(), command.reason(), command.authorizedAt(), command.expiresAt());
        assertThat(same.digest()).isEqualTo(command.digest()).matches("[a-f0-9]{64}");
        var anotherReason = new BudgetAdjustmentCommand(command.id(), command.source(), original, command.changes(), command.authorizedBy(), "different", command.authorizedAt(), command.expiresAt());
        assertThat(anotherReason.digest()).isNotEqualTo(command.digest());
        var newVersion = new BudgetLedgerPort.Snapshot(original.request(), "ledger-v3", original.observedAt(), original.validUntil(), original.positions());
        assertThat(new BudgetAdjustmentCommand(command.id(), command.source(), newVersion, command.changes(), command.authorizedBy(), command.reason(), command.authorizedAt(), command.expiresAt()).digest()).isNotEqualTo(command.digest());
        assertThat(command.toString()).doesNotContain(command.reason(), "budget-source", "1000");
    }

    @Test void appliedReceiptRequiresBothExactSidesAndUnchangedOccupiedAndConsumedAmounts() {
        var command = command(); var value = applied(command, 1, NOW.plusSeconds(3));
        assertThat(value.matches(command, false, NOW.plusSeconds(3))).isTrue();
        var first = value.changes().get(0);
        assertThat(observation(value, List.of(first)).matches(command, false, NOW.plusSeconds(3))).isFalse();
        var mutations = List.of(
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), "wrong", first.afterVersion(), first.periodReference(), DATE, first.beforeLimit(), first.afterLimit(), first.committed(), first.consumed()),
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), first.beforeVersion(), first.afterVersion(), "wrong", DATE, first.beforeLimit(), first.afterLimit(), first.committed(), first.consumed()),
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), first.beforeVersion(), first.afterVersion(), first.periodReference(), DATE.plusDays(1), first.beforeLimit(), first.afterLimit(), first.committed(), first.consumed()),
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), first.beforeVersion(), first.afterVersion(), first.periodReference(), DATE, first.beforeLimit(), money("2"), first.committed(), first.consumed()),
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), first.beforeVersion(), first.afterVersion(), first.periodReference(), DATE, first.beforeLimit(), first.afterLimit(), money("0"), first.consumed()),
                new BudgetAdjustmentObservation.AppliedChange(first.budgetReference(), first.beforeVersion(), first.afterVersion(), first.periodReference(), DATE, first.beforeLimit(), first.afterLimit(), first.committed(), money("0")));
        for (var change : mutations) assertThat(observation(value, List.of(change, value.changes().get(1))).matches(command, true, NOW.plusSeconds(3))).isFalse();
        assertThat(observation(value, List.of(value.changes().get(1), first))).isEqualTo(value);
    }

    @Test void receiptShapesAndTimeCannotInventAWriteSuccessOrAuthoritativeNotFound() {
        var command = command(); var value = applied(command, 1, NOW.plusSeconds(3));
        for (List<BudgetAdjustmentObservation.AppliedChange> changes : List.of(List.<BudgetAdjustmentObservation.AppliedChange>of(), List.of(value.changes().get(0), value.changes().get(0)))) {
            fails("INVALID_BUDGET_ADJUSTMENT_OBSERVATION", () -> observation(value, changes));
        }
        fails("INVALID_BUDGET_ADJUSTMENT_OBSERVATION", () -> new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.PENDING, 1, NOW.plusSeconds(3), "invented", null, List.of(), null));
        var missing = new BudgetAdjustmentObservation(command.id(), command.digest(), BudgetAdjustmentObservation.Status.NOT_FOUND, 0, NOW.plusSeconds(3), null, null, List.of(), null);
        assertThat(missing.matches(command, false, NOW.plusSeconds(3))).isFalse(); assertThat(missing.matches(command, true, NOW.plusSeconds(3))).isTrue();
        assertThat(value.matches(command, false, NOW.plusSeconds(2))).isFalse();
    }

    private BudgetAdjustmentObservation observation(BudgetAdjustmentObservation original, List<BudgetAdjustmentObservation.AppliedChange> changes) {
        return new BudgetAdjustmentObservation(original.operationId(), original.commandDigest(), original.status(), original.revision(), original.observedAt(), original.reference(), original.appliedAt(), changes, original.rejection());
    }
}
