package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 原消费与新冲正分开保存，凭据、期间及独立授权共同决定有效结果。
 * @author owlzhangfq@gmail.com
 */
class BudgetConsumptionReversalTest {
    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private final UUID entity = UUID.randomUUID();

    @Test void independentCommandPreservesOriginalConsumptionAndStableDigest() {
        var command = command(); var source = command.source(); var digest = source.digest();
        assertThat(command.digest()).matches("[a-f0-9]{64}").isEqualTo(copy(command, command.adjustmentId(), command.period(), "finance", "material", "reason").digest());
        assertThat(command.id()).isNotEqualTo(source.id()); assertThat(source.action()).isEqualTo(BudgetCommand.Action.CONSUME);
        assertThat(source.digest()).isEqualTo(digest); assertThat(source.position().total().value()).isEqualByComparingTo("100.00");
        assertThat(command.toString()).doesNotContain("material", "finance", "ledger-v2");
        command.requireSendAt(NOW); command.requireSendAt(command.expiresAt().minusNanos(1));
        assertThatThrownBy(() -> command.requireSendAt(command.expiresAt())).isInstanceOf(DomainException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> command.requireSendAt(NOW.minusNanos(1))).isInstanceOf(DomainException.class);
    }

    @Test void freezeReleaseAndForgedSourceCannotActAsConsumedBudget() {
        var valid = command();
        for (var action : List.of(BudgetCommand.Action.FREEZE, BudgetCommand.Action.ADJUST, BudgetCommand.Action.RELEASE)) {
            var other = new BudgetCommand(valid.source().id(), "demo", action, valid.source().position(), action == BudgetCommand.Action.FREEZE ? null : valid.source().expected());
            assertThatThrownBy(() -> make(valid.id(), other, valid.consumed(), valid.period(), "finance", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        }
        var foreign = new BudgetCommand(valid.source().id(), "foreign", BudgetCommand.Action.CONSUME, valid.source().position(), valid.source().expected());
        assertThatThrownBy(() -> make(valid.id(), foreign, valid.consumed(), valid.period(), "finance", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> make(valid.source().id(), valid.source(), valid.consumed(), valid.period(), "finance", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        var pending = new BudgetObservation(valid.source().id(), valid.source().digest(), BudgetObservation.Status.PENDING, null, null, null, null);
        assertThatThrownBy(() -> make(valid.id(), valid.source(), pending, valid.period(), "finance", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
    }

    @Test void independentActorOpenPeriodAndSendingWindowAreMandatory() {
        var valid = command();
        assertThatThrownBy(() -> make(valid.id(), valid.source(), valid.consumed(), valid.period(), "alice", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        for (var wrong : List.of(period(UUID.randomUUID(), "CNY", DATE), period(entity, "USD", DATE), period(entity, "CNY", DATE.minusDays(2)))) {
            assertThatThrownBy(() -> make(valid.id(), valid.source(), valid.consumed(), wrong, "finance", NOW, NOW.plusSeconds(120))).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> make(valid.id(), valid.source(), valid.consumed(), valid.period(), "finance", NOW, NOW.plusSeconds(301))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> make(valid.id(), valid.source(), valid.consumed(), valid.period(), "finance", NOW.minusSeconds(2), NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
    }

    @Test void digestCommitsIndependentDecisionPeriodAndEveryAuthorizationField() {
        var valid = command(); var digest = valid.digest();
        assertThat(copy(valid, UUID.randomUUID(), valid.period(), "finance", "material", "reason").digest()).isNotEqualTo(digest);
        assertThat(copy(valid, valid.adjustmentId(), period(entity, "CNY", DATE.plusDays(1)), "finance", "material", "reason").digest()).isNotEqualTo(digest);
        assertThat(copy(valid, valid.adjustmentId(), valid.period(), "finance-two", "material", "reason").digest()).isNotEqualTo(digest);
        assertThat(copy(valid, valid.adjustmentId(), valid.period(), "finance", "other", "reason").digest()).isNotEqualTo(digest);
        assertThat(copy(valid, valid.adjustmentId(), valid.period(), "finance", "material", "other").digest()).isNotEqualTo(digest);
    }

    @Test void appliedReceiptRequiresNextLedgerDistinctEvidenceAndExactAccountingPeriod() {
        var valid = command(); assertThat(applied(valid, 3, "reversal", "period", DATE, NOW).matches(valid, false, NOW.plusSeconds(1))).isTrue();
        for (var bad : List.of(applied(valid, 2, "reversal", "period", DATE, NOW), applied(valid, 4, "reversal", "period", DATE, NOW),
                applied(valid, 3, valid.consumed().reference(), "period", DATE, NOW), applied(valid, 3, "reversal", "other", DATE, NOW),
                applied(valid, 3, "reversal", "period", DATE.plusDays(1), NOW), applied(valid, 3, "reversal", "period", DATE, NOW.minusSeconds(1)))) {
            assertThat(bad.matches(valid, false, NOW.plusSeconds(1))).isFalse();
        }
        assertThat(applied(valid, 3, "reversal", "period", DATE, NOW).matches(valid, false, NOW)).isFalse();
        assertThatThrownBy(() -> applied(valid, 3, "reversal", "period", DATE, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetConsumptionReversalObservation(valid.id(), valid.digest(), BudgetConsumptionReversalObservation.Status.PENDING,
                NOW, 3L, "reversal", "period", DATE, NOW, null)).isInstanceOf(DomainException.class);
    }

    @Test void notFoundIsQueryOnlyAndExpiredAuthorizationStillAllowsOriginalQueryFacts() {
        var valid = command(); var missing = new BudgetConsumptionReversalObservation(valid.id(), valid.digest(), BudgetConsumptionReversalObservation.Status.NOT_FOUND,
                NOW.plusSeconds(500), null, null, null, null, null, null);
        assertThat(missing.matches(valid, true, NOW.plusSeconds(501))).isTrue(); assertThat(missing.matches(valid, false, NOW.plusSeconds(501))).isFalse();
        var rejected = new BudgetConsumptionReversalObservation(valid.id(), valid.digest(), BudgetConsumptionReversalObservation.Status.REJECTED, NOW,
                null, null, null, null, null, BudgetConsumptionReversalObservation.Rejection.LEDGER_VERSION_CONFLICT);
        assertThat(rejected.matches(valid, false, NOW)).isTrue();
        assertThatThrownBy(() -> new BudgetConsumptionReversalObservation(valid.id(), valid.digest(), BudgetConsumptionReversalObservation.Status.REJECTED, NOW,
                null, null, null, null, null, null)).isInstanceOf(DomainException.class);
    }

    private BudgetConsumptionReversalCommand command() {
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", entity, "CNY", DATE.minusDays(1),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "OFFICE", new CostAllocation("IT", null, new Money(new BigDecimal("100"), "CNY")))));
        var source = new BudgetCommand(UUID.randomUUID(), "demo", BudgetCommand.Action.CONSUME, position, new BudgetCommand.Expected(1, "ledger-v1"));
        var consumed = new BudgetObservation(source.id(), source.digest(), BudgetObservation.Status.APPLIED, 2L, "ledger-v2", NOW.minusSeconds(10), null);
        return make(UUID.randomUUID(), source, consumed, period(entity, "CNY", DATE), "finance", NOW, NOW.plusSeconds(120));
    }
    private AccountingPeriodPort.OpenPeriod period(UUID legalEntity, String currency, LocalDate date) {
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(legalEntity, currency, date), "period", "v1", DATE.minusDays(10), DATE.plusDays(10), NOW.minusSeconds(1), NOW.plusSeconds(300));
    }
    private BudgetConsumptionReversalCommand make(UUID id, BudgetCommand source, BudgetObservation original, AccountingPeriodPort.OpenPeriod period, String actor, Instant created, Instant expires) {
        return new BudgetConsumptionReversalCommand(id, UUID.randomUUID(), source, original, period, actor, "material", "reason", created, expires);
    }
    private BudgetConsumptionReversalCommand copy(BudgetConsumptionReversalCommand value, UUID adjustment, AccountingPeriodPort.OpenPeriod period, String actor, String reference, String reason) {
        return new BudgetConsumptionReversalCommand(value.id(), adjustment, value.source(), value.consumed(), period, actor, reference, reason, value.createdAt(), value.expiresAt());
    }
    private BudgetConsumptionReversalObservation applied(BudgetConsumptionReversalCommand command, long revision, String reference, String period, LocalDate date, Instant at) {
        return new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.APPLIED, NOW.plusSeconds(1), revision, reference, period, date, at, null);
    }
}
