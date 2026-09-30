package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPayableAdjustmentTest.adjusted;
import static io.agentflow.procurement.SupplierPayableAdjustmentTest.command;
import static io.agentflow.procurement.SupplierPayableAdjustmentTest.evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 明确裁决只恢复可证实的原调整，不重复记账、不删除历史入款分录。
 * @author owlzhangfq@gmail.com
 */
class SupplierAdjustmentDisputeResolutionTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final SupplierPayableAdjustmentOperation.ResolutionHistory EMPTY = new SupplierPayableAdjustmentOperation.ResolutionHistory(null, false);

    @Test void freshTerminalCandidateRequiresExplicitDecisionAndNeverSendsAgain() {
        var sent = sending(); var disputed = conflict(sent); var at = disputed.updatedAt().plusSeconds(1);
        var proof = adjusted(sent.command(), 2, sent.updatedAt(), "100", "80");
        var candidate = new SupplierPayableAdjustmentObservation(proof.operationId(), proof.commandDigest(), proof.status(), 2, at, proof.posting(), null);
        var current = query(disputed, candidate);
        assertThat(current.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.RECONCILING);
        var resolved = decision(current, "finance", at).resolve(current, new SupplierPayableAdjustmentOperation.ResolutionHistory(null, true));
        assertThat(resolved.adjusted()).isTrue(); assertThat(resolved.version()).isEqualTo(current.version() + 1);
        assertThat(resolved.command()).isEqualTo(sent.command()); assertThat(resolved.dispatches()).isEqualTo(1);
        assertThat(resolved.attempts()).isEqualTo(current.attempts()); assertThat(resolved.observation()).isEqualTo(candidate);
        assertThat(resolved.conflictingObservation()).isNull(); assertThat(resolved.nextAttemptAt()).isNull();
        assertThat(current.conflictingObservation()).isEqualTo(candidate);
    }

    @Test void confirmedVoucherEntriesLedgerAndBalancesCannotBeReplaced() {
        var sent = sending(); var first = adjusted(sent.command(), 1, sent.updatedAt(), "100", "80");
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt()); var p = first.posting();
        var history = new SupplierPayableAdjustmentOperation.ResolutionHistory(first, true);
        var changedEntry = new SupplierPayableAdjustmentObservation.ReturnEntry(p.entries().get(0).transactionReference(), money("20"), "changed-voucher", "changed-entry");
        for (var changed : List.of(posting(p, "different", p.ledgerVersion(), p.entries(), "100"),
                posting(p, p.adjustmentReference(), "different", p.entries(), "100"),
                posting(p, p.adjustmentReference(), p.ledgerVersion(), List.of(changedEntry), "100"),
                posting(p, p.adjustmentReference(), p.ledgerVersion(), p.entries(), "90"))) {
            var at = sent.updatedAt().plusSeconds(1);
            var candidate = new SupplierPayableAdjustmentObservation(first.operationId(), first.commandDigest(), first.status(), 2, at, changed, null);
            var disputed = query(done, candidate);
            assertThat(disputed.resolutionIssue(history, at)).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.DIFFERENT_POSTING);
            assertThatThrownBy(() -> decision(disputed, "finance", at).resolve(disputed, history)).isInstanceOf(DomainException.class);
            var restored = query(disputed, new SupplierPayableAdjustmentObservation(first.operationId(), first.commandDigest(), first.status(), 3, at.plusSeconds(1), p, null));
            assertThat(decision(restored, "finance", restored.updatedAt()).resolve(restored, history).observation().posting()).isEqualTo(p);
        }
    }

    @Test void overwrittenConflictingSuccessStillBlocksARejectionDecision() {
        var sent = sending(); var disputed = conflict(sent); var at = disputed.updatedAt().plusSeconds(1);
        var proof = adjusted(sent.command(), 2, at, "100", "80"); var success = query(disputed, proof);
        var rejected = query(success, observed(sent, SupplierPayableAdjustmentObservation.Status.REJECTED, 3, at.plusSeconds(1), SupplierPayableAdjustmentObservation.Rejection.ORIGINAL_CHANGED));
        assertThat(rejected.observation().status()).isEqualTo(SupplierPayableAdjustmentObservation.Status.PENDING);
        assertThat(rejected.resolutionIssue(new SupplierPayableAdjustmentOperation.ResolutionHistory(null, true), rejected.updatedAt()))
                .isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.ADJUSTMENT_ALREADY_OBSERVED);
        assertThat(SupplierPayableAdjustmentOperation.adjustmentRisk(success.conflictingObservation())).isTrue();
    }

    @Test void alreadyAdjustedCannotBecomeARejectionThatAllowsAnotherAdjustment() {
        var sent = sending(); var original = observed(sent, SupplierPayableAdjustmentObservation.Status.REJECTED, 1, sent.updatedAt(), SupplierPayableAdjustmentObservation.Rejection.ALREADY_ADJUSTED);
        var rejected = sent.complete(new FinanceResult.Success<>(original), sent.updatedAt());
        var disputed = query(rejected, observed(sent, SupplierPayableAdjustmentObservation.Status.REJECTED, 2, sent.updatedAt().plusSeconds(1), SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        assertThat(disputed.resolutionIssue(EMPTY, disputed.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.ADJUSTMENT_ALREADY_OBSERVED);
        assertThat(SupplierPayableAdjustmentOperation.adjustmentRisk(original)).isTrue(); assertThat(rejected.retirementBasis()).isNull();
    }

    @Test void genuineRejectionKeepsOriginalCommandAndRequiresSeparateSafeRetirement() {
        var sent = sending(); var disputed = conflict(sent);
        var current = query(disputed, observed(sent, SupplierPayableAdjustmentObservation.Status.REJECTED, 2, disputed.updatedAt().plusSeconds(1), SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        var resolved = decision(current, "finance", current.updatedAt()).resolve(current, EMPTY);
        assertThat(resolved.status()).isEqualTo(SupplierPayableAdjustmentOperation.Status.REJECTED);
        assertThat(resolved.retirementBasis()).isEqualTo(SupplierPayableAdjustmentOperation.RetirementBasis.CONFIRMED_REJECTED);
        assertThat(resolved.dispatches()).isEqualTo(1); assertThat(resolved.command()).isEqualTo(sent.command());
        assertThatThrownBy(() -> resolved.retryNotFound(resolved.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void pendingStaleAndExpiredCandidatesRequireAnotherOriginalQuery() {
        var sent = sending(); var first = adjusted(sent.command(), 3, sent.updatedAt(), "100", "80");
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt()); var history = new SupplierPayableAdjustmentOperation.ResolutionHistory(first, true);
        var pending = query(done, observed(sent, SupplierPayableAdjustmentObservation.Status.PENDING, 4, sent.updatedAt().plusSeconds(1), null));
        assertThat(pending.resolutionIssue(history, pending.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.NON_TERMINAL);
        var stale = query(pending, new SupplierPayableAdjustmentObservation(first.operationId(), first.commandDigest(), first.status(), 2, sent.updatedAt().plusSeconds(2), first.posting(), null));
        assertThat(stale.resolutionIssue(history, stale.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.STALE_EVIDENCE);
        var current = query(stale, new SupplierPayableAdjustmentObservation(first.operationId(), first.commandDigest(), first.status(), 5, sent.updatedAt().plusSeconds(3), first.posting(), null));
        var deadline = current.conflictingObservation().observedAt().plus(SupplierPayableAdjustmentOperation.DISPUTE_EVIDENCE_LIFETIME);
        assertThat(current.resolutionIssue(history, deadline.minusNanos(1))).isNull();
        assertThat(current.resolutionIssue(history, deadline)).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.EXPIRED_EVIDENCE);
        assertThatThrownBy(() -> current.resolveDispute(SupplierPayableAdjustmentObservation.Status.REJECTED, history, current.updatedAt())).isInstanceOf(DomainException.class);
        assertThat(done.resolutionIssue(history, done.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.NOT_DISPUTED);
    }

    @Test void decisionBindsOriginalCandidateVersionTenantAndIndependentActor() {
        var sent = sending(); var current = query(conflict(sent), adjusted(sent.command(), 2, sent.updatedAt().plusSeconds(2), "100", "80"));
        for (String actor : List.of("alice", "cashier")) assertThatThrownBy(() -> decision(current, actor, current.updatedAt()).resolve(current, EMPTY)).isInstanceOf(DomainException.class);
        var d = decision(current, "finance", current.updatedAt());
        var stale = new SupplierAdjustmentDisputeResolution(d.id(), d.tenantId(), d.adjustmentId(), d.disputedVersion() - 1, d.resolvedVersion() - 1, d.observation(), d.resolvedBy(), d.resolvedAt(), d.evidenceReference(), d.reason());
        assertThatThrownBy(() -> stale.resolve(current, EMPTY)).isInstanceOf(DomainException.class);
        var foreign = new SupplierAdjustmentDisputeResolution(d.id(), "other", d.adjustmentId(), d.disputedVersion(), d.resolvedVersion(), d.observation(), d.resolvedBy(), d.resolvedAt(), d.evidenceReference(), d.reason());
        assertThatThrownBy(() -> foreign.resolve(current, EMPTY)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decision(current, "finance", current.updatedAt().plusSeconds(300))).isInstanceOf(DomainException.class);
        assertThat(d.toString()).doesNotContain(d.evidenceReference(), d.reason(), "finance");
    }

    @Test void foreignOrContradictoryHistoryCannotSupportResolution() {
        var sent = sending(); var first = adjusted(sent.command(), 1, sent.updatedAt(), "100", "80");
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt());
        var rejected = query(done, observed(sent, SupplierPayableAdjustmentObservation.Status.REJECTED, 2, sent.updatedAt().plusSeconds(1), SupplierPayableAdjustmentObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        var foreign = new SupplierPayableAdjustmentObservation(UUID.randomUUID(), first.commandDigest(), first.status(), first.revision(), first.observedAt(), first.posting(), null);
        assertThat(rejected.resolutionIssue(new SupplierPayableAdjustmentOperation.ResolutionHistory(foreign, true), rejected.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.HISTORY_CHANGED);
        assertThatThrownBy(() -> new SupplierPayableAdjustmentOperation.ResolutionHistory(first, false)).isInstanceOf(DomainException.class);
        assertThat(rejected.resolutionIssue(EMPTY, rejected.updatedAt())).isEqualTo(SupplierPayableAdjustmentOperation.ResolutionIssue.ADJUSTMENT_ALREADY_OBSERVED);
    }

    private static SupplierAdjustmentDisputeResolution decision(SupplierPayableAdjustmentOperation value, String actor, Instant at) {
        return new SupplierAdjustmentDisputeResolution(UUID.randomUUID(), value.command().tenantId(), value.command().id(), value.version(), value.version() + 1,
                value.conflictingObservation(), actor, at, "erp-return-statement-1", "核对原 ERP 调整及每笔实际入款分录");
    }
    private static SupplierPayableAdjustmentOperation sending() {
        var command = command(true, "20"); var at = command.registeredAt();
        return SupplierPayableAdjustmentOperation.queue(command, at).claim(at, LEASE).readyToSend(evidence(command, at), at);
    }
    private static SupplierPayableAdjustmentOperation conflict(SupplierPayableAdjustmentOperation sent) {
        var pending = sent.complete(new FinanceResult.Success<>(observed(sent, SupplierPayableAdjustmentObservation.Status.PENDING, 1, sent.updatedAt(), null)), sent.updatedAt());
        return query(pending, observed(sent, SupplierPayableAdjustmentObservation.Status.NOT_FOUND, 0, sent.updatedAt().plusSeconds(1), null));
    }
    private static SupplierPayableAdjustmentOperation query(SupplierPayableAdjustmentOperation value, SupplierPayableAdjustmentObservation incoming) {
        return value.requestQuery(incoming.observedAt()).claim(incoming.observedAt(), LEASE).complete(new FinanceResult.Success<>(incoming), incoming.observedAt());
    }
    private static SupplierPayableAdjustmentObservation observed(SupplierPayableAdjustmentOperation sent, SupplierPayableAdjustmentObservation.Status status, long revision,
            Instant at, SupplierPayableAdjustmentObservation.Rejection rejection) {
        return new SupplierPayableAdjustmentObservation(sent.command().id(), sent.command().digest(), status, revision, at, null, rejection);
    }
    private static SupplierPayableAdjustmentObservation.Posting posting(SupplierPayableAdjustmentObservation.Posting p, String adjustment, String ledger,
            List<SupplierPayableAdjustmentObservation.ReturnEntry> entries, String before) {
        return new SupplierPayableAdjustmentObservation.Posting(adjustment, p.holdReference(), ledger, p.recognitionVoucherReference(), p.returnedAmount(), p.totalReturned(), p.netPaid(),
                money(before), money(before).minus(p.returnedAmount()), entries, p.periodReference(), p.accountingDate(), p.adjustedAt());
    }
}
