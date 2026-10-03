package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPayableSettlementTest.sending;
import static io.agentflow.procurement.SupplierPayableSettlementTest.settled;
import static org.assertj.core.api.Assertions.*;

/**
 * 结算安全结束与本地应付占用完成使用不同事实，未知不能换号，已核销不能变成取消释放。
 * @author owlzhangfq@gmail.com
 */
class SupplierSettlementClosureTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void originalReadClaimMustStopBeforeNamedRetirementAndCannotSendAfterward() {
        var command = sending().command(); var now = command.registeredAt(); var queued = SupplierPayableSettlementOperation.queue(command, now);
        var checking = queued.claim(now, LEASE);
        for (var active : new SupplierPayableSettlementOperation[] { queued, checking }) {
            assertThat(active.retirementBasis()).isEqualTo(SupplierPayableSettlementOperation.RetirementBasis.NEVER_DISPATCHED);
            assertThatThrownBy(() -> SupplierSettlementRetirement.from(active, "finance", now)).isInstanceOf(DomainException.class);
            var stopped = active.stopForRetirement(now); var decision = SupplierSettlementRetirement.from(stopped, "finance", now);
            assertThat(stopped.status()).isEqualTo(SupplierPayableSettlementOperation.Status.VOIDED); assertThat(stopped.dispatches()).isZero();
            assertThat(stopped.leaseUntil()).isNull(); assertThat(decision.matches(stopped)).isTrue();
            assertThat(decision.paymentId()).isEqualTo(command.payment().id());
            assertThatThrownBy(() -> stopped.claim(now, LEASE)).isInstanceOf(DomainException.class);
        }
    }

    @Test void unknownPendingAndAuthoritativeNotFoundDoNotPermitAReplacementAttempt() {
        var sent = sending(); var now = sent.updatedAt(); var unknown = sent.unavailable(SupplierPayableSettlementOperation.Failure.TIMEOUT, now);
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        var absent = new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.NOT_FOUND, 0L, query.updatedAt(), null, null);
        var notFound = query.complete(new FinanceResult.Success<>(absent), query.updatedAt());
        var pending = sent.complete(new FinanceResult.Success<>(new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.PENDING, 1L, now, null, null)), now);
        for (var operation : new SupplierPayableSettlementOperation[] { sent, unknown, query, notFound, pending }) {
            assertThat(operation.retirementBasis()).isNull();
            assertThatThrownBy(() -> operation.stopForRetirement(operation.updatedAt())).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> SupplierSettlementRetirement.from(operation, "finance", operation.updatedAt())).isInstanceOf(DomainException.class);
        }
    }

    @Test void exactRejectionAllowsIndependentNamedRetirementButDoesNotReleaseLocalPayable() {
        var sent = sending(); var now = sent.updatedAt(); var receipt = new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, now, null, SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        var rejected = sent.complete(new FinanceResult.Success<>(receipt), now); var decision = SupplierSettlementRetirement.from(rejected.stopForRetirement(now), "finance-2", now);
        assertThat(decision.basis()).isEqualTo(SupplierPayableSettlementOperation.RetirementBasis.CONFIRMED_REJECTED); assertThat(decision.matches(rejected)).isTrue();
        for (String actor : new String[] { "alice", "cashier" }) assertThatThrownBy(() -> SupplierSettlementRetirement.from(rejected, actor, now)).isInstanceOf(DomainException.class);
        var wrong = new SupplierSettlementRetirement(UUID.randomUUID(), decision.paymentId(), decision.operationVersion(), decision.basis(), decision.retiredBy(), decision.retiredAt());
        assertThat(wrong.matches(rejected)).isFalse(); assertThat(sent.command().payment().holdCommand().authorization().source().reservation().held()).isTrue();
    }

    @Test void alreadySettledElsewhereRequiresReconciliationInsteadOfAnotherAttempt() {
        var sent = sending(); var receipt = new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), SupplierPayableSettlementObservation.Status.REJECTED, 1L, sent.updatedAt(), null, SupplierPayableSettlementObservation.Rejection.ALREADY_SETTLED);
        var rejected = sent.complete(new FinanceResult.Success<>(receipt), sent.updatedAt());
        assertThat(rejected.retirementBasis()).isNull();
        assertThatThrownBy(() -> SupplierSettlementRetirement.from(rejected, "finance", sent.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void matchingCompletedErpSettlementFinishesReservationWithoutChangingOriginalSource() {
        var sent = sending(); var now = sent.updatedAt(); var original = sent.command().payment().holdCommand().authorization().source().reservation();
        var done = sent.complete(new FinanceResult.Success<>(settled(sent.command(), 1, now, now)), now);
        var completed = original.settle(done, now);
        assertThat(completed.held()).isFalse(); assertThat(completed.release()).isNull(); assertThat(completed.version()).isEqualTo(2);
        assertThat(completed.settlement()).isEqualTo(new ProcurementPayableReservation.Settlement(done.command().id(), done.version(), done.command().payment().id(), now));
        assertThat(completed.source()).isEqualTo(original.source()); assertThat(completed.heldAt()).isEqualTo(original.heldAt()); assertThat(original.held()).isTrue();
        assertThatThrownBy(() -> completed.settle(done, now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> completed.release(ProcurementPayableReservation.ReleaseReason.CANCELLED, "finance", now)).isInstanceOf(DomainException.class);
        assertThat(done.retirementBasis()).isNull();
    }

    @Test void unknownOtherSourcePrematureCompletionAndCombinedReleaseCannotFinishReservation() {
        var sent = sending(); var now = sent.updatedAt(); var original = sent.command().payment().holdCommand().authorization().source().reservation();
        var done = sent.complete(new FinanceResult.Success<>(settled(sent.command(), 1, now, now)), now);
        assertThatThrownBy(() -> original.settle(sent, now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> original.settle(done, now.minusNanos(1))).isInstanceOf(DomainException.class);
        var other = new ProcurementPayableReservation(UUID.randomUUID(), original.source(), 1, original.heldAt(), null, null);
        assertThatThrownBy(() -> other.settle(done, now)).isInstanceOf(DomainException.class);
        var proof = original.settle(done, now).settlement(); var release = new ProcurementPayableReservation.Release(ProcurementPayableReservation.ReleaseReason.CANCELLED, "finance", now);
        assertThatThrownBy(() -> new ProcurementPayableReservation(original.id(), original.source(), 2, original.heldAt(), release, proof)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProcurementPayableReservation(original.id(), original.source(), 1, original.heldAt(), null, proof)).isInstanceOf(DomainException.class);
    }
}
