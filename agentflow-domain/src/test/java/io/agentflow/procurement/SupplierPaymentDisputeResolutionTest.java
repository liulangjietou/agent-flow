package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccountsPort;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPaymentTestData.AUTHORIZED_AT;
import static io.agentflow.procurement.SupplierPaymentTestData.authorization;
import static io.agentflow.procurement.SupplierPaymentTestData.current;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 裁决只恢复原付款的可信终态，历史到账、真实退回与独立复核人不得被新回执覆盖。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentDisputeResolutionTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final SupplierPaymentOperation.ResolutionHistory EMPTY = new SupplierPaymentOperation.ResolutionHistory(null, null, false);

    @Test void pendingConflictCanAdoptFreshOriginalSuccessWithoutResendingOrReplacingHold() {
        var sent = sending(); var command = sent.command(); var at = sent.updatedAt();
        var pending = sent.complete(new FinanceResult.Success<>(observed(command, PaymentObservation.Status.PENDING, 1, at, "bank-1", null)), at);
        var missing = query(pending, observed(command, PaymentObservation.Status.NOT_FOUND, 0, at.plusSeconds(1), null, null));
        var candidate = observed(command, PaymentObservation.Status.SUCCEEDED, 2, at.plusSeconds(2), "bank-1", "receipt-1");
        var disputed = query(missing, candidate);
        assertThat(disputed.status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
        var decision = decision(disputed, "finance", candidate.observedAt()); var resolved = decision.resolve(disputed, EMPTY);
        assertThat(resolved.status()).isEqualTo(SupplierPaymentOperation.Status.SUCCEEDED);
        assertThat(resolved.version()).isEqualTo(disputed.version() + 1); assertThat(resolved.command()).isEqualTo(command);
        assertThat(resolved.dispatches()).isEqualTo(1); assertThat(resolved.attempts()).isEqualTo(disputed.attempts());
        assertThat(resolved.observation()).isEqualTo(candidate); assertThat(resolved.conflictingObservation()).isNull();
        assertThat(resolved.nextAttemptAt()).isNull(); assertThat(command.held().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
        assertThat(disputed.conflictingObservation()).isEqualTo(candidate);
    }

    @Test void priorSuccessRequiresTheExactOriginalReceiptAndFreshHigherVersionCanRestoreIt() {
        var sent = sending(); var original = observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 1, sent.updatedAt(), "bank-1", "receipt-1");
        var paid = sent.complete(new FinanceResult.Success<>(original), sent.updatedAt());
        var disputed = query(paid, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 2, sent.updatedAt().plusSeconds(1), "bank-1", "different-receipt"));
        var history = new SupplierPaymentOperation.ResolutionHistory(original, null, true);
        assertThat(disputed.resolutionIssue(history, disputed.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.DIFFERENT_SETTLEMENT);
        assertThatThrownBy(() -> decision(disputed, "finance", disputed.updatedAt()).resolve(disputed, history)).isInstanceOf(DomainException.class);
        var refreshed = query(disputed, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 3, sent.updatedAt().plusSeconds(2), "bank-1", "receipt-1"));
        assertThat(refreshed.status()).isEqualTo(SupplierPaymentOperation.Status.RECONCILING);
        assertThat(decision(refreshed, "finance", refreshed.updatedAt()).resolve(refreshed, history).settleable()).isTrue();
    }

    @Test void historicalFundingIncludingOverwrittenConflictCannotBeResolvedAsNeverPaid() {
        var sent = sending(); var pending = sent.complete(new FinanceResult.Success<>(observed(sent.command(), PaymentObservation.Status.PENDING, 1, sent.updatedAt(), "bank-1", null)), sent.updatedAt());
        var success = observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 2, sent.updatedAt().plusSeconds(1), "different-bank", "receipt-1");
        var conflict = query(pending, success);
        var failed = query(conflict, observed(sent.command(), PaymentObservation.Status.FAILED, 3, sent.updatedAt().plusSeconds(2), "bank-1", null));
        assertThat(failed.observation().status()).isEqualTo(PaymentObservation.Status.PENDING);
        assertThat(failed.conflictingObservation().status()).isEqualTo(PaymentObservation.Status.FAILED);
        assertThat(failed.resolutionIssue(new SupplierPaymentOperation.ResolutionHistory(null, null, true), failed.updatedAt()))
                .isEqualTo(SupplierPaymentOperation.ResolutionIssue.FUNDING_ALREADY_OBSERVED);
        assertThat(failed.resolveDispute(PaymentObservation.Status.FAILED, EMPTY, failed.updatedAt()).status()).isEqualTo(SupplierPaymentOperation.Status.FAILED);
    }

    @Test void confirmedReturnCannotBecomePaidAgainOrChangeOriginalReturnReceipt() {
        var sent = sending(); var original = observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 1, sent.updatedAt(), "bank-1", "receipt-1");
        var paid = sent.complete(new FinanceResult.Success<>(original), sent.updatedAt());
        var returned = query(paid, observed(sent.command(), PaymentObservation.Status.REVERSED, 2, sent.updatedAt().plusSeconds(1), "bank-1", "return-1"));
        var history = new SupplierPaymentOperation.ResolutionHistory(original, returned.observation(), true);
        var wrong = query(returned, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 3, sent.updatedAt().plusSeconds(2), "bank-1", "receipt-1"));
        assertThat(wrong.resolutionIssue(history, wrong.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.RETURN_ALREADY_OBSERVED);
        var different = query(wrong, observed(sent.command(), PaymentObservation.Status.REVERSED, 4, sent.updatedAt().plusSeconds(3), "bank-1", "return-2"));
        assertThat(different.resolutionIssue(history, different.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.RETURN_ALREADY_OBSERVED);
        var restored = query(different, observed(sent.command(), PaymentObservation.Status.REVERSED, 5, sent.updatedAt().plusSeconds(4), "bank-1", "return-1"));
        assertThat(decision(restored, "finance", restored.updatedAt()).resolve(restored, history).status()).isEqualTo(SupplierPaymentOperation.Status.REVERSED);
    }

    @Test void nonTerminalStaleAndExpiredEvidenceMustBeQueriedAgain() {
        var sent = sending(); var original = observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 3, sent.updatedAt(), "bank-1", "receipt-1");
        var paid = sent.complete(new FinanceResult.Success<>(original), sent.updatedAt()); var history = new SupplierPaymentOperation.ResolutionHistory(original, null, true);
        var pending = query(paid, observed(sent.command(), PaymentObservation.Status.PENDING, 4, sent.updatedAt().plusSeconds(1), "bank-1", null));
        assertThat(pending.resolutionIssue(history, pending.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.NON_TERMINAL);
        var stale = query(pending, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 2, sent.updatedAt().plusSeconds(2), "bank-1", "receipt-1"));
        assertThat(stale.resolutionIssue(history, stale.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.STALE_EVIDENCE);
        var fresh = query(stale, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 5, sent.updatedAt().plusSeconds(3), "bank-1", "receipt-1"));
        var deadline = fresh.conflictingObservation().observedAt().plus(SupplierPaymentOperation.DISPUTE_EVIDENCE_LIFETIME);
        assertThat(fresh.resolutionIssue(history, deadline.minusNanos(1))).isNull();
        assertThat(fresh.resolutionIssue(history, deadline)).isEqualTo(SupplierPaymentOperation.ResolutionIssue.EXPIRED_EVIDENCE);
        assertThatThrownBy(() -> fresh.resolveDispute(PaymentObservation.Status.FAILED, history, fresh.updatedAt())).isInstanceOf(DomainException.class);
        assertThat(paid.resolutionIssue(history, paid.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.NOT_DISPUTED);
    }

    @Test void independentActorAndExactCandidateVersionsAreMandatory() {
        var sent = sending(); var pending = sent.complete(new FinanceResult.Success<>(observed(sent.command(), PaymentObservation.Status.PENDING, 1, sent.updatedAt(), "bank-1", null)), sent.updatedAt());
        var missing = query(pending, observed(sent.command(), PaymentObservation.Status.NOT_FOUND, 0, sent.updatedAt().plusSeconds(1), null, null));
        var disputed = query(missing, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 2, sent.updatedAt().plusSeconds(2), "bank-1", "receipt-1"));
        for (String actor : List.of("alice", "cashier")) assertThatThrownBy(() -> decision(disputed, actor, disputed.updatedAt()).resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        var correct = decision(disputed, "finance", disputed.updatedAt());
        var stale = new SupplierPaymentDisputeResolution(correct.id(), correct.tenantId(), correct.paymentId(), correct.disputedVersion() - 1,
                correct.resolvedVersion() - 1, correct.observation(), correct.resolvedBy(), correct.resolvedAt(), correct.evidenceReference(), correct.reason());
        assertThatThrownBy(() -> stale.resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        var wrongTenant = new SupplierPaymentDisputeResolution(correct.id(), "other", correct.paymentId(), correct.disputedVersion(), correct.resolvedVersion(),
                correct.observation(), correct.resolvedBy(), correct.resolvedAt(), correct.evidenceReference(), correct.reason());
        assertThatThrownBy(() -> wrongTenant.resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decision(disputed, "finance", disputed.updatedAt().plusSeconds(300))).isInstanceOf(DomainException.class);
    }

    @Test void foreignHistoricalReceiptAndDifferentBankCannotAuthorizeResolution() {
        var sent = sending(); var original = observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 1, sent.updatedAt(), "bank-1", "receipt-1");
        var paid = sent.complete(new FinanceResult.Success<>(original), sent.updatedAt());
        var dispute = query(paid, observed(sent.command(), PaymentObservation.Status.SUCCEEDED, 2, sent.updatedAt().plusSeconds(1), "bank-2", "receipt-1"));
        assertThat(dispute.resolutionIssue(EMPTY, dispute.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.DIFFERENT_PAYMENT);
        var foreign = observed(sending().command(), PaymentObservation.Status.SUCCEEDED, 1, sent.updatedAt(), "bank-1", "receipt-1");
        assertThat(dispute.resolutionIssue(new SupplierPaymentOperation.ResolutionHistory(foreign, null, true), dispute.updatedAt())).isEqualTo(SupplierPaymentOperation.ResolutionIssue.HISTORY_CHANGED);
        assertThatThrownBy(() -> new SupplierPaymentOperation.ResolutionHistory(original, null, false)).isInstanceOf(DomainException.class);
    }

    private static SupplierPaymentDisputeResolution decision(SupplierPaymentOperation value, String actor, Instant at) {
        return new SupplierPaymentDisputeResolution(UUID.randomUUID(), value.command().tenantId(), value.command().id(), value.version(), value.version() + 1,
                value.conflictingObservation(), actor, at, "bank-statement-1", "核对原银行资金终态");
    }
    private static SupplierPaymentOperation query(SupplierPaymentOperation value, PaymentObservation incoming) {
        return value.requestQuery(incoming.observedAt()).claim(incoming.observedAt(), LEASE).complete(new FinanceResult.Success<>(incoming), incoming.observedAt());
    }
    private static PaymentObservation observed(SupplierPaymentCommand command, PaymentObservation.Status status, long revision, Instant at, String bank, String receipt) {
        boolean settled = status == PaymentObservation.Status.SUCCEEDED || status == PaymentObservation.Status.REVERSED;
        return new PaymentObservation(command.id(), command.digest(), status, revision, at, bank, settled ? command.amount() : null,
                settled ? command.payee().accountDigest() : null, settled ? command.registeredAt() : null, receipt,
                status == PaymentObservation.Status.FAILED ? PaymentObservation.Failure.PAYMENT_REJECTED : null);
    }
    private static SupplierPaymentOperation sending() {
        var holdCommand = new SupplierPayableHoldCommand(authorization()); var at = AUTHORIZED_AT.plusSeconds(3);
        var held = new SupplierPayableHoldObservation(holdCommand.id(), holdCommand.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, at,
                "hold-1", "ledger-1", holdCommand.authorization().source().amount(), holdCommand.authorization().payable().account().accountDigest(), AUTHORIZED_AT.plusSeconds(1), null);
        var original = SupplierPayableHoldOperation.queue(holdCommand, AUTHORIZED_AT).claim(AUTHORIZED_AT, LEASE).complete(new FinanceResult.Success<>(held), at);
        var debit = new PaymentAccountsPort.DebitAccount("debit-1", "基本户", "****5678", "CNY", "debit-v1");
        var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(holdCommand.authorization().payable().request().legalEntityId(), "CNY", "cashier"),
                "directory-v1", at, at.plusSeconds(600), List.of(debit));
        var command = SupplierPaymentCommand.register(original, held, directory, debit.reference(), "cashier", at);
        var evidence = SupplierPaymentEvidence.checked(command, directory, current(holdCommand.authorization().source(), "30", at), held, at);
        return SupplierPaymentOperation.queue(command, at).claim(at, LEASE).readyToSend(evidence, at);
    }
}
