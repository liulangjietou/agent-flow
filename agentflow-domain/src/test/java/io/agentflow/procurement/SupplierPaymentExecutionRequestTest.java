package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentAccountsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPaymentTestData.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 出纳原选择的准备租约、账户版本、原预留及授权时限风险。
 * @author owlzhangfq@gmail.com
 */
class SupplierPaymentExecutionRequestTest {
    private static final Instant NOW = AUTHORIZED_AT.plusSeconds(3);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");

    @Test void onlyOriginalConfirmedHoldAndIndependentCashierCanStart() {
        var hold = original(); var queued = queue(hold);
        assertThat(queued.input().authorizationId()).isEqualTo(hold.command().id()); assertThat(queued.input().holdVersion()).isEqualTo(hold.version());
        assertThat(queued.ownsAuthorization()).isTrue(); assertThat(queued.toString()).doesNotContain("debit-1", "cashier");
        var unconfirmed = SupplierPayableHoldOperation.queue(hold.command(), AUTHORIZED_AT);
        assertThatThrownBy(() -> queue(unconfirmed)).isInstanceOf(DomainException.class);
        for (var actor : List.of("alice", "finance")) {
            assertThatThrownBy(() -> SupplierPaymentExecutionRequest.queue(UUID.randomUUID(), hold, actor, "debit-1", "v1", NOW)).isInstanceOf(DomainException.class);
        }
    }

    @Test void preparationTimeoutAndReadLeaseExpiryKeepTheExactOriginalSelection() {
        var queued = queue(original()); var claimed = queued.claim(NOW, LEASE);
        var unavailable = claimed.unavailable(SupplierPaymentExecutionRequest.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(unavailable.status()).isEqualTo(SupplierPaymentExecutionRequest.Status.QUEUED); assertThat(unavailable.input()).isEqualTo(queued.input());
        assertThatThrownBy(() -> unavailable.claim(unavailable.nextAttemptAt().minusNanos(1), LEASE)).isInstanceOf(DomainException.class);
        var expired = claimed.expireLease(claimed.leaseUntil()); assertThat(expired.input()).isEqualTo(queued.input());
        assertThat(expired.claim(expired.nextAttemptAt(), LEASE).attempts()).isEqualTo(2);
    }

    @Test void freshSourcesRegisterOnlyTheChosenAccountVersionAndPreserveAuthorizationIdentity() {
        var original = original(); var claimed = queue(original).claim(NOW, LEASE); var at = NOW.plusSeconds(1);
        var command = claimed.register(original, observed(original, at), directory(original, DEBIT, at), current(original.command().authorization().source(), "30", at), at);
        var ready = claimed.ready(command, at);
        assertThat(ready.status()).isEqualTo(SupplierPaymentExecutionRequest.Status.READY); assertThat(ready.ownsAuthorization()).isTrue();
        assertThat(command.id()).isEqualTo(original.command().id()); assertThat(command.registeredFrom(original)).isTrue();
        var replaced = new PaymentAccountsPort.DebitAccount(DEBIT.reference(), DEBIT.displayName(), DEBIT.maskedAccount(), "CNY", "v2");
        assertThatThrownBy(() -> claimed.register(original, observed(original, at), directory(original, replaced, at), current(original.command().authorization().source(), "30", at), at))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUPPLIER_PAYMENT_EVIDENCE_CHANGED"));
        assertThatThrownBy(() -> ready.claim(at, LEASE)).isInstanceOf(DomainException.class);
    }

    @Test void stoppedChoiceReleasesOwnershipButNeverCanBecomeRegisteredItself() {
        var claimed = queue(original()).claim(NOW, LEASE);
        for (var stopped : List.of(claimed.block(NOW), claimed.voidSource(NOW), claimed.expireAuthorization(NOW))) {
            assertThat(stopped.ownsAuthorization()).isFalse(); assertThat(stopped.input()).isEqualTo(claimed.input());
            assertThatThrownBy(() -> stopped.claim(NOW, LEASE)).isInstanceOf(DomainException.class);
        }
    }

    @Test void expiryAndLateResultCannotCreateOrConfirmABankCommand() {
        var original = original(); var claimed = queue(original).claim(NOW, LEASE); var deadline = claimed.leaseUntil();
        assertThatThrownBy(() -> claimed.register(original, observed(original, deadline), directory(original, DEBIT, deadline), current(original.command().authorization().source(), "30", deadline), deadline))
                .isInstanceOf(DomainException.class);
        var authDeadline = original.command().authorization().expiresAt();
        assertThatThrownBy(() -> SupplierPaymentExecutionRequest.queue(UUID.randomUUID(), original, "cashier", "debit-1", "v1", authDeadline)).isInstanceOf(DomainException.class);
        var command = claimed.register(original, observed(original, NOW), directory(original, DEBIT, NOW), current(original.command().authorization().source(), "30", NOW), NOW);
        assertThat(claimed.ready(command, deadline).status()).isEqualTo(SupplierPaymentExecutionRequest.Status.QUEUED);
        var nextClaim = claimed.expireLease(deadline).claim(deadline, LEASE);
        assertThatThrownBy(() -> nextClaim.ready(command, deadline)).isInstanceOf(DomainException.class);
    }

    @Test void sendPreflightCannotUseAReadOlderThanTheLatestLocallyConfirmedHold() {
        var original = original(); var claimed = queue(original).claim(NOW, LEASE);
        var command = claimed.register(original, observed(original, NOW), directory(original, DEBIT, NOW), current(original.command().authorization().source(), "30", NOW), NOW);
        var recheckAt = NOW.plusSeconds(2); var query = original.requestQuery(recheckAt).claim(recheckAt, LEASE);
        var newer = query.complete(new FinanceResult.Success<>(observed(original, recheckAt)), recheckAt);
        assertThat(command.matchesCurrentHold(newer, observed(original, NOW.plusSeconds(1)), recheckAt)).isFalse();
        assertThat(command.matchesCurrentHold(newer, observed(original, recheckAt), recheckAt)).isTrue();
        assertThat(command.registeredFrom(newer)).isFalse();
    }

    private SupplierPayableHoldOperation original() {
        var command = new SupplierPayableHoldCommand(authorization()); var sending = SupplierPayableHoldOperation.queue(command, AUTHORIZED_AT).claim(AUTHORIZED_AT, LEASE);
        return sending.complete(new FinanceResult.Success<>(observed(sending, AUTHORIZED_AT.plusSeconds(1))), AUTHORIZED_AT.plusSeconds(1));
    }
    private SupplierPaymentExecutionRequest queue(SupplierPayableHoldOperation hold) { return SupplierPaymentExecutionRequest.queue(UUID.randomUUID(), hold, "cashier", "debit-1", "v1", NOW); }
    private SupplierPayableHoldObservation observed(SupplierPayableHoldOperation hold, Instant now) {
        return new SupplierPayableHoldObservation(hold.command().id(), hold.command().digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now,
                "hold-1", "ledger-1", hold.command().authorization().source().amount(), hold.command().authorization().payable().account().accountDigest(), AUTHORIZED_AT.plusSeconds(1), null);
    }
    private PaymentAccountsPort.Directory directory(SupplierPayableHoldOperation hold, PaymentAccountsPort.DebitAccount debit, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(hold.command().authorization().payable().request().legalEntityId(), "CNY", "cashier"), "directory-v1", now, now.plusSeconds(600), List.of(debit));
    }
}
