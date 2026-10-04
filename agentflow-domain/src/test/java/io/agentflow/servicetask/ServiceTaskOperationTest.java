package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖丢失回执、领取到期、重复结果和错误来源，成功结果不等同于审批或财务结论。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskOperationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T09:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);

    @Test
    void timeoutQueriesOriginalOperationAndAcceptedResultIsImmutable() {
        var sent = queue().claim(NOW, LEASE);
        var unknown = sent.unavailable(ServiceTaskOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(unknown.status()).isEqualTo(ServiceTaskOperation.Status.UNKNOWN);
        assertThatThrownBy(() -> unknown.claim(NOW.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
        var applied = query.complete(observed(query, ServiceTaskObservation.Status.APPLIED), query.updatedAt().plusSeconds(1));
        assertThat(applied.status()).isEqualTo(ServiceTaskOperation.Status.APPLIED);
        assertThat(applied.terminal()).isTrue();
        assertThat(applied.input()).isSameAs(sent.input());
        assertThatThrownBy(() -> applied.claim(applied.updatedAt(), LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> applied.complete(observed(applied, ServiceTaskObservation.Status.REJECTED), applied.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test
    void crashOrLateSuccessOnlyAllowsRecoveryByQueryAndExplicitNotFoundRetriesSameIdentity() {
        var sent = queue().claim(NOW, LEASE);
        var expired = sent.complete(observed(sent, ServiceTaskObservation.Status.APPLIED), sent.leaseUntil());
        assertThat(expired.failure()).isEqualTo(ServiceTaskOperation.Failure.LEASE_EXPIRED);
        assertThat(expired.observation()).isNull();
        var query = expired.claim(expired.nextAttemptAt(), LEASE);
        assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
        var missing = query.complete(observed(query, ServiceTaskObservation.Status.NOT_FOUND), query.updatedAt().plusSeconds(1));
        var resent = missing.claim(missing.nextAttemptAt(), LEASE);
        assertThat(resent.status()).isEqualTo(ServiceTaskOperation.Status.EXECUTING);
        assertThat(resent.input()).isSameAs(sent.input());
        var invalid = resent.complete(observed(resent, ServiceTaskObservation.Status.NOT_FOUND), resent.updatedAt().plusSeconds(1));
        assertThat(invalid.status()).isEqualTo(ServiceTaskOperation.Status.UNKNOWN);
        assertThat(invalid.failure()).isEqualTo(ServiceTaskOperation.Failure.INVALID_RESPONSE);
    }

    @Test
    void knownRejectionStopsAndDoesNotAuthorizeANewExecution() {
        var sent = queue().claim(NOW, LEASE);
        var rejected = sent.complete(observed(sent, ServiceTaskObservation.Status.REJECTED), NOW.plusSeconds(1));
        assertThat(rejected.status()).isEqualTo(ServiceTaskOperation.Status.REJECTED);
        assertThat(rejected.terminal()).isTrue();
        assertThat(rejected.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> rejected.claim(NOW.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
    }

    @Test
    void pendingAndRepeatedUnavailabilityRemainUnknownWithBoundedBackoff() {
        var sent = queue().claim(NOW, LEASE);
        var current = sent.complete(observed(sent, ServiceTaskObservation.Status.PENDING), NOW.plusSeconds(1));
        assertThat(current.observation().status()).isEqualTo(ServiceTaskObservation.Status.PENDING);
        assertThat(current.failure()).isNull();
        for (int i = 0; i < 12; i++) {
            var query = current.claim(current.nextAttemptAt(), LEASE);
            assertThat(query.status()).isEqualTo(ServiceTaskOperation.Status.QUERYING);
            current = query.unavailable(ServiceTaskOperation.Failure.CONNECTION, query.updatedAt().plusSeconds(1));
            assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt()).toSeconds()).isBetween(5L, 300L);
            assertThat(current.input()).isSameAs(sent.input());
        }
        assertThat(Duration.between(current.updatedAt(), current.nextAttemptAt())).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void mismatchedOrFutureReceiptCannotBeAccepted() {
        var sent = queue().claim(NOW, LEASE); var command = sent.input().command();
        for (var receipt : java.util.List.of(
                new ServiceTaskObservation(UUID.randomUUID(), command.digest(), ServiceTaskObservation.Status.APPLIED, "receipt-1", NOW),
                new ServiceTaskObservation(command.id(), "b".repeat(64), ServiceTaskObservation.Status.APPLIED, "receipt-1", NOW),
                new ServiceTaskObservation(command.id(), command.digest(), ServiceTaskObservation.Status.APPLIED, "receipt-1", NOW.plusSeconds(3)))) {
            var invalid = sent.complete(receipt, NOW.plusSeconds(1));
            assertThat(invalid.status()).isEqualTo(ServiceTaskOperation.Status.UNKNOWN);
            assertThat(invalid.failure()).isEqualTo(ServiceTaskOperation.Failure.INVALID_RESPONSE);
        }
    }

    @Test
    void rejectsClockReversalAndInvalidLeaseBeforeChangingState() {
        var queued = queue(); var sent = queued.claim(NOW, LEASE);
        for (var lease : java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(6))) {
            assertThatThrownBy(() -> queued.claim(NOW, lease)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> sent.complete(observed(sent, ServiceTaskObservation.Status.APPLIED), NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.expire(NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> sent.unavailable(ServiceTaskOperation.Failure.LEASE_EXPIRED, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
    }

    @Test
    void restorationRejectsUnknownWithoutEvidenceAndTerminalWithoutAttempt() {
        var input = queue().input();
        assertThatThrownBy(() -> new ServiceTaskOperation(input, 2, ServiceTaskOperation.Status.UNKNOWN, 1, NOW, NOW, NOW, null, null, null))
                .isInstanceOf(DomainException.class);
        var applied = new ServiceTaskObservation(input.command().id(), input.command().digest(), ServiceTaskObservation.Status.APPLIED, "receipt-1", NOW);
        assertThatThrownBy(() -> new ServiceTaskOperation(input, 1, ServiceTaskOperation.Status.APPLIED, 0, NOW, NOW, null, null, applied, null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskOperation(input, 3, ServiceTaskOperation.Status.QUEUED, 1, NOW, NOW, NOW, null, null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void stoppedWorkflowCancelsOnlyCommandsProvenUnsent() {
        var queued = queue();
        var cancelled = queued.cancelUnsent(NOW.plusSeconds(1));
        assertThat(cancelled.status()).isEqualTo(ServiceTaskOperation.Status.CANCELLED);
        assertThat(cancelled.terminal()).isTrue();
        var sent = queued.claim(NOW, LEASE);
        assertThatThrownBy(() -> sent.cancelUnsent(NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        var unknown = sent.unavailable(ServiceTaskOperation.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThatThrownBy(() -> unknown.cancelUnsent(NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        var missing = query.complete(observed(query, ServiceTaskObservation.Status.NOT_FOUND), query.updatedAt().plusSeconds(1));
        assertThat(missing.cancelUnsent(missing.updatedAt()).observation().status()).isEqualTo(ServiceTaskObservation.Status.NOT_FOUND);
    }

    @Test
    void receiptShapeCannotCarryUnboundedRemoteTextOrFalseTerminalFacts() {
        var command = queue().input().command();
        assertThatThrownBy(() -> new ServiceTaskObservation(command.id(), command.digest(), ServiceTaskObservation.Status.PENDING, "receipt-1", NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskObservation(command.id(), command.digest(), ServiceTaskObservation.Status.REJECTED, null, null))
                .isInstanceOf(DomainException.class);
        for (String reference : java.util.List.of("x".repeat(129), "<script>value</script>", "response with secrets", "receipt\nother")) {
            assertThatThrownBy(() -> new ServiceTaskObservation(command.id(), command.digest(), ServiceTaskObservation.Status.APPLIED, reference, NOW))
                    .isInstanceOf(DomainException.class);
        }
    }

    private ServiceTaskOperation queue() { return ServiceTaskOperation.queue(new ServiceTaskOperation.Input(ServiceTaskCommandTest.command(), "c".repeat(64)), NOW); }
    private ServiceTaskObservation observed(ServiceTaskOperation operation, ServiceTaskObservation.Status status) {
        boolean terminal = status == ServiceTaskObservation.Status.APPLIED || status == ServiceTaskObservation.Status.REJECTED;
        return new ServiceTaskObservation(operation.input().command().id(), operation.input().command().digest(), status,
                terminal ? "receipt-1" : null, terminal ? operation.updatedAt() : null);
    }
}
