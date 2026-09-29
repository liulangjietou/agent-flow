package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseResourceAdjustment;
import io.agentflow.expense.ExpenseResourceAdjustmentPreparation;
import io.agentflow.expense.ExpenseResourceAdjustmentRetirement;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 期间读取与明确授权分开，过期或未知不能绕过为新写命令；安全结束只停止无副作用的原意图。
 * @author owlzhangfq@gmail.com
 */
class ExpenseResourceAdjustmentPreparationTest {
    private static final Instant NOW = Instant.parse("2026-09-29T16:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);

    @Test void preparingAnOpenPeriodDoesNotCreateACommandAndAuthorizationIsSingleUse() {
        var queued = queue(); var ready = queued.claim(NOW, Duration.ofSeconds(30)).ready(period(queued, NOW, NOW.plusSeconds(60)), NOW.plusSeconds(1));
        assertThat(ready.command()).isNull(); assertThat(ready.status()).isEqualTo(ExpenseResourceAdjustmentPreparation.Status.READY);
        assertInvalid(ready::authorizedInput);
        var authorized = ready.authorize(NOW.plusSeconds(5)); var command = authorized.command();
        assertThat(command.createdAt()).isEqualTo(NOW.plusSeconds(5)); assertThat(command.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(command.id()).isEqualTo(queued.input().id()); assertThat(command.adjustmentId()).isEqualTo(command.id());
        assertThat(authorized.authorizedInput().basis()).isEqualTo(queued.input().basis());
        assertInvalid(() -> authorized.authorize(NOW.plusSeconds(6))); assertInvalid(() -> authorized.voidSource(NOW.plusSeconds(6)));
    }

    @Test void evidenceAgeCannotBeRenewedByDelayedCompletionOrAuthorization() {
        var queued = queue(); var old = period(queued, NOW.minusSeconds(290), NOW.plusSeconds(1000));
        var ready = queued.claim(NOW, Duration.ofSeconds(30)).ready(old, NOW.plusSeconds(1));
        assertThat(ready.usable(NOW.plusSeconds(9))).isTrue(); assertThat(ready.usable(NOW.plusSeconds(10))).isFalse();
        assertInvalid(() -> ready.authorize(NOW.plusSeconds(10)));
        var command = ready.authorize(NOW.plusSeconds(9)).command(); assertThat(command.expiresAt()).isEqualTo(NOW.plusSeconds(10));
        assertInvalid(() -> queued.claim(NOW, Duration.ofSeconds(30)).ready(period(queued, NOW.minusSeconds(301), NOW.plusSeconds(1000)), NOW));
    }

    @Test void timeoutAndForeignPeriodRemainNonAuthorizableAndSourceActorIsIndependent() {
        var queued = queue(); var running = queued.claim(NOW, Duration.ofSeconds(30)); var period = period(queued, NOW, NOW.plusSeconds(60));
        var expired = running.ready(period, NOW.plusSeconds(30)); assertThat(expired.issue()).isEqualTo("TIMEOUT"); assertThat(expired.command()).isNull();
        var other = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(UUID.randomUUID(), "CNY", DATE), period.periodReference(), period.sourceVersion(),
                period.startsOn(), period.endsOn(), period.observedAt(), period.validUntil());
        assertInvalid(() -> running.ready(other, NOW)); assertInvalid(() -> running.authorize(NOW));
        var input = queued.input();
        assertInvalid(() -> new ExpenseResourceAdjustmentPreparation.Input(input.id(), input.basis(), DATE, "cashier", "evidence", "reason", NOW));
        assertInvalid(() -> new ExpenseResourceAdjustmentPreparation.Input(input.id(), input.basis(), DATE.minusDays(3), "finance", "evidence", "reason", NOW));
    }

    @Test void stoppedUnsentOrRejectedCommandsCanRetireWithoutReversingAnyResources() {
        var prepared = authorized(); var input = prepared.authorizedInput(); var adjustment = ExpenseResourceAdjustment.begin(input);
        var queued = BudgetConsumptionReversalOperation.queue(input.budget(), NOW.plusSeconds(1));
        assertThat(queued.safelyUnexecuted()).isTrue();
        assertInvalid(() -> adjustment.retire(queued, NOW.plusSeconds(2)));
        var stopped = queued.voidBeforeSend(NOW.plusSeconds(2));
        var retirement = new ExpenseResourceAdjustmentRetirement(adjustment, stopped, "finance-two", "cancel-evidence", "重新选择记账期间", NOW.plusSeconds(3));
        var retired = retirement.after(); assertThat(retired.status()).isEqualTo(ExpenseResourceAdjustment.Status.RETIRED); assertThat(retired.resourcesReversed()).isFalse();
        assertThat(retired.input()).isEqualTo(input); assertThat(retired.budgetReversal()).isNull();
        assertInvalid(() -> retired.requireReview("LATE_RESULT", NOW.plusSeconds(4)));
        var claimed = queued.claim(NOW.plusSeconds(1), Duration.ofSeconds(30)); var command = input.budget().command();
        var rejected = claimed.complete(new FinanceResult.Success<>(new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.REJECTED,
                NOW.plusSeconds(2), null, null, null, null, null, BudgetConsumptionReversalObservation.Rejection.ACCOUNTING_PERIOD_CLOSED)), NOW.plusSeconds(2));
        assertThat(adjustment.retire(rejected, NOW.plusSeconds(3)).status()).isEqualTo(ExpenseResourceAdjustment.Status.RETIRED);
        var expired = queued.claim(command.expiresAt(), Duration.ofSeconds(30)); assertThat(expired.safelyUnexecuted()).isTrue();
        assertThat(adjustment.retire(expired, command.expiresAt()).status()).isEqualTo(ExpenseResourceAdjustment.Status.RETIRED);
    }

    @Test void unknownAndNotFoundCannotRetireEvenAfterAuthorizationExpiry() {
        var prepared = authorized(); var input = prepared.authorizedInput(); var adjustment = ExpenseResourceAdjustment.begin(input);
        var running = BudgetConsumptionReversalOperation.queue(input.budget(), NOW.plusSeconds(1)).claim(NOW.plusSeconds(1), Duration.ofSeconds(30));
        var unknown = running.unavailable(BudgetConsumptionReversalOperation.Failure.TIMEOUT, NOW.plusSeconds(2));
        assertThat(unknown.safelyUnexecuted()).isFalse(); assertInvalid(() -> adjustment.retire(unknown, NOW.plusSeconds(500)));
        var query = unknown.claim(unknown.nextAttemptAt(), Duration.ofSeconds(30)); var command = input.budget().command();
        var missing = query.complete(new FinanceResult.Success<>(new BudgetConsumptionReversalObservation(command.id(), command.digest(), BudgetConsumptionReversalObservation.Status.NOT_FOUND,
                query.updatedAt(), null, null, null, null, null, null)), query.updatedAt());
        assertThat(missing.safelyUnexecuted()).isFalse(); assertInvalid(() -> adjustment.retire(missing, NOW.plusSeconds(500)));
        var retried = missing.retryNotFound(NOW.plusSeconds(10)).voidBeforeSend(NOW.plusSeconds(11));
        assertThat(retried.safelyUnexecuted()).isFalse(); assertInvalid(() -> adjustment.retire(retried, NOW.plusSeconds(12)));
    }

    private ExpenseResourceAdjustmentPreparation queue() {
        return ExpenseResourceAdjustmentPreparation.queue(new ExpenseResourceAdjustmentPreparation.Input(UUID.randomUUID(), new ExpenseResourceAdjustmentTest().basis(false), DATE, "finance", "evidence", "完整取消报销", NOW));
    }
    private ExpenseResourceAdjustmentPreparation authorized() {
        var queued = queue(); return queued.claim(NOW, Duration.ofSeconds(30)).ready(period(queued, NOW, NOW.plusSeconds(60)), NOW).authorize(NOW.plusSeconds(1));
    }
    private AccountingPeriodPort.OpenPeriod period(ExpenseResourceAdjustmentPreparation queued, Instant observed, Instant expiry) {
        return new AccountingPeriodPort.OpenPeriod(queued.input().periodRequest(), "2026-09", "period-v1", DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), observed, expiry);
    }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(DomainException.class); }
}
