package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 例外授权只替换一次明确拒绝的原命令，未知结果不能变为预算成功或二次授权。
 * @author owlzhangfq@gmail.com
 */
class BudgetExceptionApprovalTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(15);
    private static final String TARGET = "a".repeat(64);
    private final BudgetExceptionPolicy policy = new BudgetExceptionPolicy("policy-flex-1");

    @Test
    void authorizationRequiresTerminalOriginalOfferSamePolicyAndRealDecisionIdentity() {
        var original = refused(null); var approval = authorize(original, policy, NOW.plusSeconds(2));
        assertThat(approval.originalOperationId()).isEqualTo(original.input().command().id());
        assertThat(approval.originalCommandDigest()).isEqualTo(original.input().command().digest());
        assertThat(approval.targetDigest()).isEqualTo(TARGET); assertThat(approval.offerReference()).isEqualTo("offer-1");
        assertThatThrownBy(() -> authorize(original, null, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorize(original, new BudgetExceptionPolicy("other-policy"), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorize(original, policy, NOW)).isInstanceOf(DomainException.class);
        var pending = BudgetOperation.queue(original.input(), NOW);
        assertThatThrownBy(() -> authorize(pending, policy, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var rigid = pending.claim(NOW, LEASE).complete(new FinanceResult.Success<>(new BudgetObservation(original.input().command().id(),
                original.input().command().digest(), BudgetObservation.Status.REJECTED, null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT)), NOW.plusSeconds(1));
        assertThatThrownBy(() -> authorize(rigid, policy, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> BudgetExceptionApproval.authorize(original, policy, "", "owner", UUID.randomUUID(), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> BudgetExceptionApproval.authorize(original, policy, "task", "", UUID.randomUUID(), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> BudgetExceptionApproval.authorize(original, policy, "task", "owner", null, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
    }

    @Test
    void authorizationCannotBeReusedForAnotherCommandContextTargetOrEarlierQueueTime() {
        var original = refused(null); var before = original.input().command(); var approval = authorize(original, policy, NOW.plusSeconds(2));
        var next = new BudgetCommand(UUID.randomUUID(), before.tenantId(), before.action(), before.position(), before.expected(), approval);
        assertThat(next.position()).isEqualTo(before.position());
        assertThatThrownBy(() -> new BudgetCommand(before.id(), before.tenantId(), before.action(), before.position(), before.expected(), approval)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetCommand(next.id(), "foreign", before.action(), before.position(), before.expected(), approval)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetCommand(next.id(), before.tenantId(), before.action(), before.position(), new BudgetCommand.Expected(1, "ledger"), approval)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetCommand(next.id(), before.tenantId(), BudgetCommand.Action.RELEASE, before.position(), new BudgetCommand.Expected(1, "ledger"), approval)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetOperation.Input(next, "b".repeat(64))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> BudgetOperation.queue(new BudgetOperation.Input(next, TARGET), NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void approvedTimeoutQueriesSameProofAndOnlyActualAppliedCreatesBudgetFact() {
        var original = refused(null); var command = original.input().command(); var approval = authorize(original, policy, NOW.plusSeconds(2));
        var input = new BudgetOperation.Input(new BudgetCommand(UUID.randomUUID(), command.tenantId(), command.action(), command.position(), command.expected(), approval), TARGET);
        var unfunded = BudgetOccupation.begin(original.input()).complete(original); var reserved = unfunded.enqueue(input);
        assertThat(reserved.frozenFor(command.position())).isFalse();
        var claimed = BudgetOperation.queue(input, NOW.plusSeconds(2)).claim(NOW.plusSeconds(2), LEASE);
        var unknown = claimed.complete(new FinanceResult.Unavailable<>(FinanceResult.Failure.TIMEOUT), NOW.plusSeconds(3));
        var query = unknown.claim(unknown.nextAttemptAt(), LEASE);
        assertThat(query.status()).isEqualTo(BudgetOperation.Status.QUERYING); assertThat(query.input()).isEqualTo(input);
        assertThat(query.input().command().exceptionApproval()).isEqualTo(approval);
        var applied = query.complete(new FinanceResult.Success<>(new BudgetObservation(input.command().id(), input.command().digest(),
                BudgetObservation.Status.APPLIED, 1L, "ledger-1", query.updatedAt(), null)), query.updatedAt().plusSeconds(1));
        assertThat(reserved.complete(applied).frozenFor(command.position())).isTrue();
        assertThat(original.status()).isEqualTo(BudgetOperation.Status.REJECTED);
    }

    @Test
    void secondRefusalIsTerminalAndCannotCreateAnotherExceptionAuthorization() {
        var original = refused(null); var command = original.input().command(); var approval = authorize(original, policy, NOW.plusSeconds(2));
        var retry = new BudgetCommand(UUID.randomUUID(), command.tenantId(), command.action(), command.position(), command.expected(), approval);
        var rejected = refused(retry);
        assertThat(rejected.status()).isEqualTo(BudgetOperation.Status.REJECTED);
        assertThatThrownBy(() -> authorize(rejected, policy, NOW.plusSeconds(5))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> rejected.claim(NOW.plusSeconds(5), LEASE)).isInstanceOf(DomainException.class);
    }

    private BudgetExceptionApproval authorize(BudgetOperation original, BudgetExceptionPolicy source, Instant at) {
        return BudgetExceptionApproval.authorize(original, source, "task-budget", "budget-owner", UUID.randomUUID(), at);
    }
    private BudgetOperation refused(BudgetCommand supplied) {
        var position = new BudgetPrecheckPort.Request(UUID.randomUUID(), 1, 2, "alice", UUID.randomUUID(), "CNY", LocalDate.of(2026, 10, 4),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("IT", null, new Money(new BigDecimal("100"), "CNY")))));
        var command = supplied == null ? new BudgetCommand(UUID.randomUUID(), "tenant", BudgetCommand.Action.FREEZE, position, null) : supplied;
        var at = supplied == null ? NOW : NOW.plusSeconds(3);
        return BudgetOperation.queue(new BudgetOperation.Input(command, TARGET), at).claim(at, LEASE).complete(new FinanceResult.Success<>(
                new BudgetObservation(command.id(), command.digest(), BudgetObservation.Status.REJECTED, null, null, null,
                        BudgetObservation.Rejection.BUDGET_EXCEPTION_REQUIRED, new BudgetExceptionOffer(policy.reference(), "offer-1"))), at.plusSeconds(1));
    }
}
