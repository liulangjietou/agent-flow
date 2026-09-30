package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.money;
import static io.agentflow.procurement.SupplierPayableSettlementTest.sending;
import static io.agentflow.procurement.SupplierPayableSettlementTest.settled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原核销人工裁决恢复可证实的终态，不改写已见凭证、不伪造未核销，也不产生第二次发送。
 * @author owlzhangfq@gmail.com
 */
class SupplierSettlementDisputeResolutionTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final SupplierPayableSettlementOperation.ResolutionHistory EMPTY = new SupplierPayableSettlementOperation.ResolutionHistory(null, false);

    @Test void pendingConflictAdoptsFreshSettlementOnlyAfterExplicitDecision() {
        var sent = sending(); var pending = pending(sent); var at = sent.updatedAt().plusSeconds(1);
        var missing = query(pending, observed(sent, SupplierPayableSettlementObservation.Status.NOT_FOUND, 0, at, null));
        var candidate = settled(sent.command(), 2, at.plusSeconds(1), sent.updatedAt());
        var disputed = query(missing, candidate);
        assertThat(disputed.status()).isEqualTo(SupplierPayableSettlementOperation.Status.RECONCILING);
        var resolved = decision(disputed, "finance", disputed.updatedAt()).resolve(disputed, new SupplierPayableSettlementOperation.ResolutionHistory(null, true));
        assertThat(resolved.settled()).isTrue(); assertThat(resolved.version()).isEqualTo(disputed.version() + 1);
        assertThat(resolved.command()).isEqualTo(sent.command()); assertThat(resolved.dispatches()).isEqualTo(1);
        assertThat(resolved.attempts()).isEqualTo(disputed.attempts()); assertThat(resolved.observation()).isEqualTo(candidate);
        assertThat(resolved.conflictingObservation()).isNull(); assertThat(resolved.nextAttemptAt()).isNull();
        assertThat(disputed.conflictingObservation()).isEqualTo(candidate);
    }

    @Test void confirmedPostingCannotChangeVoucherLedgerBalancesOrSettlementReference() {
        var sent = sending(); var first = settled(sent.command(), 1, sent.updatedAt(), sent.updatedAt());
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt()); var p = first.posting(); var at = sent.updatedAt().plusSeconds(1);
        var history = new SupplierPayableSettlementOperation.ResolutionHistory(first, true);
        for (var changed : List.of(posting(p, "different", p.voucherReference(), p.ledgerVersion(), p.settledBefore().value().toPlainString()),
                posting(p, p.settlementReference(), "different", p.ledgerVersion(), p.settledBefore().value().toPlainString()),
                posting(p, p.settlementReference(), p.voucherReference(), "different", p.settledBefore().value().toPlainString()),
                posting(p, p.settlementReference(), p.voucherReference(), p.ledgerVersion(), "29"))) {
            var candidate = new SupplierPayableSettlementObservation(first.operationId(), first.commandDigest(), first.status(), 2L, at, changed, null);
            var conflict = query(done, candidate);
            assertThat(conflict.resolutionIssue(history, at)).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.DIFFERENT_POSTING);
            assertThatThrownBy(() -> decision(conflict, "finance", at).resolve(conflict, history)).isInstanceOf(DomainException.class);
            var restored = query(conflict, settled(sent.command(), 3, at.plusSeconds(1), sent.updatedAt()));
            assertThat(decision(restored, "finance", restored.updatedAt()).resolve(restored, history).settled()).isTrue();
        }
    }

    @Test void overwrittenConflictingPostingStillPreventsNoSettlementDecision() {
        var sent = sending(); var first = pending(sent); var at = sent.updatedAt().plusSeconds(1);
        var missing = query(first, observed(sent, SupplierPayableSettlementObservation.Status.NOT_FOUND, 0, at, null));
        var posting = query(missing, settled(sent.command(), 2, at.plusSeconds(1), sent.updatedAt()));
        var refused = query(posting, observed(sent, SupplierPayableSettlementObservation.Status.REJECTED, 3, at.plusSeconds(2), SupplierPayableSettlementObservation.Rejection.PAYMENT_CHANGED));
        assertThat(refused.observation().status()).isEqualTo(SupplierPayableSettlementObservation.Status.PENDING);
        assertThat(refused.resolutionIssue(new SupplierPayableSettlementOperation.ResolutionHistory(null, true), refused.updatedAt()))
                .isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.SETTLEMENT_ALREADY_OBSERVED);
        assertThat(SupplierPayableSettlementOperation.settlementRisk(posting.conflictingObservation())).isTrue();
    }

    @Test void alreadySettledRejectionCannotBeDowngradedToSafeRejection() {
        var sent = sending(); var first = observed(sent, SupplierPayableSettlementObservation.Status.REJECTED, 1, sent.updatedAt(), SupplierPayableSettlementObservation.Rejection.ALREADY_SETTLED);
        var rejected = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt());
        var candidate = query(rejected, observed(sent, SupplierPayableSettlementObservation.Status.REJECTED, 2, sent.updatedAt().plusSeconds(1), SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        assertThat(candidate.resolutionIssue(EMPTY, candidate.updatedAt())).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.SETTLEMENT_ALREADY_OBSERVED);
        assertThat(SupplierPayableSettlementOperation.settlementRisk(first)).isTrue(); assertThat(rejected.retirementBasis()).isNull();
    }

    @Test void genuineRejectionWithNoPostingCanBeChosenWithoutRetryingOrReleasingAutomatically() {
        var sent = sending(); var missing = query(pending(sent), observed(sent, SupplierPayableSettlementObservation.Status.NOT_FOUND, 0, sent.updatedAt().plusSeconds(1), null));
        var candidate = query(missing, observed(sent, SupplierPayableSettlementObservation.Status.REJECTED, 2, sent.updatedAt().plusSeconds(2), SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        var resolved = decision(candidate, "finance", candidate.updatedAt()).resolve(candidate, EMPTY);
        assertThat(resolved.status()).isEqualTo(SupplierPayableSettlementOperation.Status.REJECTED);
        assertThat(resolved.retirementBasis()).isEqualTo(SupplierPayableSettlementOperation.RetirementBasis.CONFIRMED_REJECTED);
        assertThat(resolved.dispatches()).isEqualTo(1); assertThat(resolved.command()).isEqualTo(sent.command());
        assertThatThrownBy(() -> resolved.retryNotFound(resolved.updatedAt())).isInstanceOf(DomainException.class);
    }

    @Test void nonTerminalStaleAndExpiredCandidateRequireAnotherOriginalQuery() {
        var sent = sending(); var first = settled(sent.command(), 3, sent.updatedAt(), sent.updatedAt());
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt()); var history = new SupplierPayableSettlementOperation.ResolutionHistory(first, true);
        var pending = query(done, observed(sent, SupplierPayableSettlementObservation.Status.PENDING, 4, sent.updatedAt().plusSeconds(1), null));
        assertThat(pending.resolutionIssue(history, pending.updatedAt())).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.NON_TERMINAL);
        var stale = query(pending, settled(sent.command(), 2, sent.updatedAt().plusSeconds(2), sent.updatedAt()));
        assertThat(stale.resolutionIssue(history, stale.updatedAt())).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.STALE_EVIDENCE);
        var fresh = query(stale, settled(sent.command(), 5, sent.updatedAt().plusSeconds(3), sent.updatedAt()));
        var deadline = fresh.conflictingObservation().observedAt().plus(SupplierPayableSettlementOperation.DISPUTE_EVIDENCE_LIFETIME);
        assertThat(fresh.resolutionIssue(history, deadline.minusNanos(1))).isNull();
        assertThat(fresh.resolutionIssue(history, deadline)).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.EXPIRED_EVIDENCE);
        assertThatThrownBy(() -> fresh.resolveDispute(SupplierPayableSettlementObservation.Status.REJECTED, history, fresh.updatedAt())).isInstanceOf(DomainException.class);
        assertThat(done.resolutionIssue(history, done.updatedAt())).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.NOT_DISPUTED);
    }

    @Test void originalVersionTenantIndependentActorAndCandidateMustMatchDecision() {
        var sent = sending(); var missing = query(pending(sent), observed(sent, SupplierPayableSettlementObservation.Status.NOT_FOUND, 0, sent.updatedAt().plusSeconds(1), null));
        var disputed = query(missing, settled(sent.command(), 2, sent.updatedAt().plusSeconds(2), sent.updatedAt()));
        for (String actor : List.of("alice", "cashier")) assertThatThrownBy(() -> decision(disputed, actor, disputed.updatedAt()).resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        var d = decision(disputed, "finance", disputed.updatedAt());
        var stale = new SupplierSettlementDisputeResolution(d.id(), d.tenantId(), d.settlementId(), d.disputedVersion() - 1, d.resolvedVersion() - 1, d.observation(), d.resolvedBy(), d.resolvedAt(), d.evidenceReference(), d.reason());
        assertThatThrownBy(() -> stale.resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        var foreign = new SupplierSettlementDisputeResolution(d.id(), "other", d.settlementId(), d.disputedVersion(), d.resolvedVersion(), d.observation(), d.resolvedBy(), d.resolvedAt(), d.evidenceReference(), d.reason());
        assertThatThrownBy(() -> foreign.resolve(disputed, EMPTY)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decision(disputed, "finance", disputed.updatedAt().plusSeconds(300))).isInstanceOf(DomainException.class);
        assertThat(d.toString()).doesNotContain(d.evidenceReference(), d.reason(), "finance");
    }

    @Test void foreignHistoryAndContradictoryHistoryCannotSupportResolution() {
        var sent = sending(); var first = settled(sent.command(), 1, sent.updatedAt(), sent.updatedAt());
        var done = sent.complete(new FinanceResult.Success<>(first), sent.updatedAt());
        var rejected = query(done, observed(sent, SupplierPayableSettlementObservation.Status.REJECTED, 2, sent.updatedAt().plusSeconds(1), SupplierPayableSettlementObservation.Rejection.ACCOUNTING_PERIOD_CLOSED));
        var foreign = new SupplierPayableSettlementObservation(UUID.randomUUID(), first.commandDigest(), first.status(), first.revision(), first.observedAt(), first.posting(), null);
        assertThat(rejected.resolutionIssue(new SupplierPayableSettlementOperation.ResolutionHistory(foreign, true), rejected.updatedAt()))
                .isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.HISTORY_CHANGED);
        assertThatThrownBy(() -> new SupplierPayableSettlementOperation.ResolutionHistory(first, false)).isInstanceOf(DomainException.class);
        assertThat(rejected.resolutionIssue(EMPTY, rejected.updatedAt())).isEqualTo(SupplierPayableSettlementOperation.ResolutionIssue.SETTLEMENT_ALREADY_OBSERVED);
    }

    private static SupplierSettlementDisputeResolution decision(SupplierPayableSettlementOperation value, String actor, Instant at) {
        return new SupplierSettlementDisputeResolution(UUID.randomUUID(), value.command().tenantId(), value.command().id(), value.version(), value.version() + 1,
                value.conflictingObservation(), actor, at, "erp-statement-1", "核对原 ERP 核销及付款凭证");
    }
    private static SupplierPayableSettlementOperation pending(SupplierPayableSettlementOperation sent) {
        return sent.complete(new FinanceResult.Success<>(observed(sent, SupplierPayableSettlementObservation.Status.PENDING, 1, sent.updatedAt(), null)), sent.updatedAt());
    }
    private static SupplierPayableSettlementOperation query(SupplierPayableSettlementOperation value, SupplierPayableSettlementObservation incoming) {
        return value.requestQuery(incoming.observedAt()).claim(incoming.observedAt(), LEASE).complete(new FinanceResult.Success<>(incoming), incoming.observedAt());
    }
    private static SupplierPayableSettlementObservation observed(SupplierPayableSettlementOperation sent, SupplierPayableSettlementObservation.Status status, long revision,
            Instant at, SupplierPayableSettlementObservation.Rejection rejection) {
        return new SupplierPayableSettlementObservation(sent.command().id(), sent.command().digest(), status, revision, at, null, rejection);
    }
    private static SupplierPayableSettlementObservation.Posting posting(SupplierPayableSettlementObservation.Posting p, String settlement, String voucher, String ledger, String before) {
        return new SupplierPayableSettlementObservation.Posting(settlement, p.holdReference(), ledger, p.settledAmount(), money(before), money(before).plus(p.settledAmount()),
                p.bankPaymentReference(), p.bankReceiptReference(), voucher, p.periodReference(), p.accountingDate(), p.settledAt());
    }
}
