package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 调整准备保存明确授权，读取、登记与再次发送复查分开，安全结束保留原事实。
 * @author owlzhangfq@gmail.com
 */
class SupplierAdjustmentPreparationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void preparationReadsBeforeCommandRegistrationButSendingRequiresAnotherFreshCheck() {
        var template = SupplierPayableAdjustmentTest.command(false, "20"); var at = template.registeredAt();
        var queue = SupplierAdjustmentPreparation.queue(template.id(), template.source(), "finance", template.period().request().accountingDate(), at);
        var running = queue.claim(at.plusSeconds(1), LEASE); var evidence = SupplierPayableAdjustmentTest.evidence(template, at.plusSeconds(2));
        var command = running.input().command(evidence, at.plusSeconds(3));
        assertThat(command.registeredAt()).isEqualTo(at.plusSeconds(3)); assertThat(command.source()).isEqualTo(template.source());
        assertThat(running.ready(command, at.plusSeconds(3)).status()).isEqualTo(SupplierAdjustmentPreparation.Status.READY);
        assertThat(evidence.matches(command, at.plusSeconds(3))).isFalse();
        var operation = SupplierPayableAdjustmentOperation.queue(command, at.plusSeconds(3)).claim(at.plusSeconds(3), LEASE);
        assertThatThrownBy(() -> operation.readyToSend(evidence, at.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThat(operation.readyToSend(SupplierPayableAdjustmentTest.evidence(command, at.plusSeconds(4)), at.plusSeconds(4)).dispatches()).isEqualTo(1);
    }

    @Test void readyCannotSubstituteSourceIdentityActorDateOrRegisterAfterLeaseExpired() {
        var template = SupplierPayableAdjustmentTest.command(true, "20"); var at = template.registeredAt();
        var running = SupplierAdjustmentPreparation.queue(template.id(), template.source(), "finance", template.period().request().accountingDate(), at).claim(at, LEASE);
        var proof = SupplierPayableAdjustmentTest.evidence(template, at); var command = running.input().command(proof, at);
        assertThatThrownBy(() -> running.ready(new SupplierPayableAdjustmentCommand(UUID.randomUUID(), command.source(), command.period(), "finance", at), at)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.ready(new SupplierPayableAdjustmentCommand(command.id(), command.source(), command.period(), "finance-2", at), at)).isInstanceOf(DomainException.class);
        var ledger = command.source().returns();
        var changed = new SupplierPaymentReturns(ledger.request(), ledger.version() + 1, ledger.entries(), true, ledger.createdAt(), at);
        var other = new SupplierPayableAdjustmentSource(changed, command.source().settlement(), null);
        assertThatThrownBy(() -> running.ready(new SupplierPayableAdjustmentCommand(command.id(), other, command.period(), "finance", at), at)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.ready(command, running.leaseUntil())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.input().command(proof, proof.validUntil())).isInstanceOf(DomainException.class);
        for (var actor : List.of("alice", "cashier")) assertThatThrownBy(() -> SupplierAdjustmentPreparation.queue(UUID.randomUUID(), template.source(), actor, template.period().request().accountingDate(), at)).isInstanceOf(DomainException.class);
    }

    @Test void readFailuresOnlyRetryOriginalIntentAndClosedPeriodBlocksWithoutCreatingCommand() {
        var template = SupplierPayableAdjustmentTest.command(false, "20"); var at = template.registeredAt();
        var running = SupplierAdjustmentPreparation.queue(template.id(), template.source(), "finance", template.period().request().accountingDate(), at).claim(at, LEASE);
        var retry = running.unavailable(SupplierAdjustmentPreparation.Issue.TIMEOUT, at.plusSeconds(1));
        assertThat(retry.active()).isTrue(); assertThat(retry.input()).isSameAs(running.input()); assertThat(retry.nextAttemptAt()).isAfter(retry.updatedAt());
        var blocked = running.block(SupplierAdjustmentPreparation.Issue.ACCOUNTING_PERIOD_REJECTED, at.plusSeconds(1));
        assertThat(blocked.active()).isFalse(); assertThat(blocked.status()).isEqualTo(SupplierAdjustmentPreparation.Status.BLOCKED);
        assertThat(running.expireLease(running.leaseUntil()).status()).isEqualTo(SupplierAdjustmentPreparation.Status.QUEUED);
        assertThat(running.voidSource(at).active()).isFalse();
        assertThatThrownBy(() -> blocked.claim(at.plusSeconds(2), LEASE)).isInstanceOf(DomainException.class);
    }

    @Test void safeRetirementRequiresStoppedOriginalVersionAndIndependentFinance() {
        var command = SupplierPayableAdjustmentTest.command(true, "20"); var at = command.registeredAt();
        var queue = SupplierPayableAdjustmentOperation.queue(command, at); var checking = queue.claim(at, LEASE);
        assertThatThrownBy(() -> SupplierAdjustmentRetirement.from(checking, "finance-2", at)).isInstanceOf(DomainException.class);
        var stopped = checking.stopForRetirement(at); var decision = SupplierAdjustmentRetirement.from(stopped, "finance-2", at);
        assertThat(decision.matches(stopped)).isTrue(); assertThat(decision.matches(queue)).isFalse(); assertThat(stopped.dispatches()).isZero();
        for (var actor : List.of("alice", "cashier")) assertThatThrownBy(() -> SupplierAdjustmentRetirement.from(stopped, actor, at)).isInstanceOf(DomainException.class);
        var sent = checking.readyToSend(SupplierPayableAdjustmentTest.evidence(command, at), at);
        assertThatThrownBy(() -> SupplierAdjustmentRetirement.from(sent.unavailable(SupplierPayableAdjustmentOperation.Failure.TIMEOUT, at), "finance-2", at)).isInstanceOf(DomainException.class);
        var noEffect = new SupplierPayableAdjustmentObservation(command.id(), command.digest(), SupplierPayableAdjustmentObservation.Status.REJECTED, 1, at, null, SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED);
        var rejected = sent.complete(new FinanceResult.Success<>(noEffect), at);
        assertThat(SupplierAdjustmentRetirement.from(rejected, "finance-2", at).basis()).isEqualTo(SupplierPayableAdjustmentOperation.RetirementBasis.CONFIRMED_REJECTED);
    }
}
