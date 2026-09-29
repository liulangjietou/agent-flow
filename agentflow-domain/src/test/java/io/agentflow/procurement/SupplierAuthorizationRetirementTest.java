package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.ProcurementPayableTest.*;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 授权安全结束不能把未查清的 ERP 预留当作没有发生，旧依据与处理人分别保留。
 * @author owlzhangfq@gmail.com
 */
class SupplierAuthorizationRetirementTest {
    @Test void queueMustStopBeforeRetirementAndIndependentFinanceCannotChangeItsSource() {
        var queued = queued();
        assertThat(queued.retirementBasis()).isEqualTo(SupplierPayableHoldOperation.RetirementBasis.NEVER_DISPATCHED);
        fails(() -> SupplierAuthorizationRetirement.from(queued, "finance", AUTHORIZED_AT));
        var stopped = queued.stopForRetirement(AUTHORIZED_AT);
        assertThat(stopped.command()).isSameAs(queued.command()); assertThat(stopped.failure()).isEqualTo(SupplierPayableHoldOperation.Failure.FINANCE_RETIRED);
        var retirement = SupplierAuthorizationRetirement.from(stopped, "finance", AUTHORIZED_AT);
        assertThat(retirement.matches(stopped)).isTrue(); assertThat(retirement.matches(queued)).isFalse();
        fails(() -> SupplierAuthorizationRetirement.from(stopped, "alice", AUTHORIZED_AT));
        fails(() -> SupplierAuthorizationRetirement.from(stopped, "finance", AUTHORIZED_AT.minusNanos(1)));
        assertThat(retirement.toString()).doesNotContain("finance", "alice", "supplier-account");
    }

    @Test void definitiveRejectionRetiresWithoutAlteringTheOriginalReceipt() {
        var sending = queued().claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        var receipt = new SupplierPayableHoldObservation(sending.command().id(), sending.command().digest(), SupplierPayableHoldObservation.Status.REJECTED, 1L,
                AUTHORIZED_AT, null, null, null, null, null, SupplierPayableHoldObservation.Rejection.PAYABLE_VERSION_CONFLICT);
        var rejected = sending.complete(new FinanceResult.Success<>(receipt), AUTHORIZED_AT);
        assertThat(rejected.stopForRetirement(AUTHORIZED_AT.plusSeconds(1))).isSameAs(rejected);
        var decision = SupplierAuthorizationRetirement.from(rejected, "other-finance", AUTHORIZED_AT.plusSeconds(1));
        assertThat(decision.basis()).isEqualTo(SupplierPayableHoldOperation.RetirementBasis.CONFIRMED_REJECTED); assertThat(decision.matches(rejected)).isTrue();
        assertThat(rejected.observation()).isSameAs(receipt);
    }

    @Test void unknownNotFoundHeldAndPostDispatchStoppedStatesCannotBeRetired() {
        var sending = queued().claim(AUTHORIZED_AT, Duration.ofSeconds(30));
        var unknown = sending.unavailable(SupplierPayableHoldOperation.Failure.TIMEOUT, AUTHORIZED_AT);
        var querying = unknown.claim(unknown.nextAttemptAt(), Duration.ofSeconds(30));
        var absent = querying.complete(new FinanceResult.Success<>(new SupplierPayableHoldObservation(querying.command().id(), querying.command().digest(), SupplierPayableHoldObservation.Status.NOT_FOUND,
                0L, querying.updatedAt(), null, null, null, null, null, null)), querying.updatedAt());
        var held = sending.complete(new FinanceResult.Success<>(new SupplierPayableHoldObservation(sending.command().id(), sending.command().digest(), SupplierPayableHoldObservation.Status.HELD,
                1L, AUTHORIZED_AT, "erp-hold", "ledger-2", money("70"), account().accountDigest(), AUTHORIZED_AT, null)), AUTHORIZED_AT);
        for (var unsafe : List.of(sending, unknown, querying, absent, held, absent.retryNotFound(absent.updatedAt()).voidBeforeSend(absent.updatedAt()))) {
            assertThat(unsafe.retirementBasis()).isNull(); fails(() -> unsafe.stopForRetirement(unsafe.updatedAt()));
            fails(() -> SupplierAuthorizationRetirement.from(unsafe, "finance", unsafe.updatedAt()));
        }
    }

    @Test void unusedExpiryAndSourceStopKeepTheirOriginalReasonAfterRetirement() {
        var queued = queued();
        for (var stopped : List.of(queued.claim(queued.command().sendDeadline(), Duration.ofSeconds(30)), queued.voidBeforeSend(AUTHORIZED_AT))) {
            assertThat(stopped.stopForRetirement(stopped.updatedAt())).isSameAs(stopped);
            assertThat(SupplierAuthorizationRetirement.from(stopped, "finance", stopped.updatedAt()).basis()).isEqualTo(SupplierPayableHoldOperation.RetirementBasis.NEVER_DISPATCHED);
        }
    }

    private static SupplierPayableHoldOperation queued() { return SupplierPayableHoldOperation.queue(new SupplierPayableHoldCommand(authorization()), AUTHORIZED_AT); }
    private static void fails(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_AUTHORIZATION_RETIREMENT_UNSAFE"));
    }
}
