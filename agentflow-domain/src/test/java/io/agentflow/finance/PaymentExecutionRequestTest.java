package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 出纳的持久选择与账户复查不会自行改变授权或产生已付款事实。
 * @author owlzhangfq@gmail.com
 */
class PaymentExecutionRequestTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:02Z");
    private static final Duration LEASE = Duration.ofSeconds(20);
    private final Fixture fixture = fixture();

    @Test void requestFixesOnlyOriginalAuthorizationCashierAndChosenAccountVersion() {
        var queued = queue(); assertThat(queued.status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED); assertThat(queued.attempts()).isZero();
        assertThat(queued.input().authorizationId()).isEqualTo(fixture.authorization().terms().id());
        assertThat(queued.input().cashier()).isEqualTo("cashier"); assertThat(queued.input().debitVersion()).isEqualTo("v1");
        assertThat(fixture.authorization().execution()).isNull(); assertThat(queued.toString()).doesNotContain("debit-1", "cashier");
        for (String user : List.of("alice", "finance")) assertThatThrownBy(() -> PaymentExecutionRequest.queue(UUID.randomUUID(), fixture.authorization(), user, "debit-1", "v1", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentExecutionRequest.queue(UUID.randomUUID(), fixture.authorization(), "cashier", "", "v1", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentExecutionRequest.queue(UUID.randomUUID(), fixture.authorization(), "cashier", "debit-1", "v1", fixture.authorization().decision().expiresAt())).isInstanceOf(DomainException.class);
    }
    @Test void networkAndLeaseFailureRecheckTheSameSelectionWithoutRegisteringPayment() {
        var queued = queue(); var running = queued.claim(NOW, LEASE);
        var retry = running.unavailable(PaymentExecutionRequest.Failure.TIMEOUT, NOW.plusSeconds(1));
        assertThat(retry.status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED); assertThat(retry.input()).isEqualTo(queued.input());
        var next = retry.claim(retry.nextAttemptAt(), LEASE); var recovered = next.expireLease(next.leaseUntil());
        assertThat(recovered.status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED); assertThat(recovered.attempts()).isEqualTo(2); assertThat(recovered.input()).isEqualTo(queued.input());
        assertThatThrownBy(() -> running.claim(NOW, LEASE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.unavailable(PaymentExecutionRequest.Failure.ACCOUNT_CHANGED, NOW)).isInstanceOf(DomainException.class);
    }
    @Test void currentMatchingAccountCanRegisterExactlyTheConfirmedChoiceAndReadyIsNotPaid() {
        var running = queue().claim(NOW, LEASE); var executed = running.register(fixture.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), NOW);
        assertThat(executed.execution().command().id()).isEqualTo(fixture.authorization().terms().id());
        assertThat(executed.execution().command().debitAccountReference()).isEqualTo("debit-1"); assertThat(executed.status()).isEqualTo(PaymentAuthorization.Status.EXECUTION_REGISTERED);
        var ready = running.ready(executed, NOW); assertThat(ready.status()).isEqualTo(PaymentExecutionRequest.Status.READY); assertThat(ready.active()).isFalse();
        assertThat(PaymentOperation.queue(executed, NOW).status()).isEqualTo(PaymentOperation.Status.QUEUED);
        assertThatThrownBy(() -> ready.register(fixture.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ready.voidSource(NOW)).isInstanceOf(DomainException.class);
    }
    @Test void changedChoiceOtherCashierAndOtherAuthorizationCannotSatisfyTheOriginalRequest() {
        var running = queue().claim(NOW, LEASE); var directory = fixture.directory(); var debit = directory.accounts().get(0);
        var changed = new PaymentAccountsPort.Directory(directory.request(), "v2", directory.observedAt(), directory.validUntil(),
                List.of(new PaymentAccountsPort.DebitAccount(debit.reference(), debit.displayName(), debit.maskedAccount(), debit.currency(), "v2")));
        assertThatThrownBy(() -> running.register(fixture.authorization(), changed, fixture.payee(), fixture.voucher(), NOW)).isInstanceOf(DomainException.class);
        var otherCashier = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(directory.request().legalEntityId(), "CNY", "other"), "v1", NOW, NOW.plusSeconds(60), directory.accounts());
        assertThatThrownBy(() -> running.register(fixture.authorization(), otherCashier, fixture.payee(), fixture.voucher(), NOW)).isInstanceOf(DomainException.class);
        var otherAuthorization = fixture();
        assertThatThrownBy(() -> running.register(otherAuthorization.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), NOW)).isInstanceOf(DomainException.class);
        var otherExecuted = otherAuthorization.authorization().registerExecution("cashier", otherAuthorization.directory(), "debit-1", otherAuthorization.payee(), otherAuthorization.voucher(), NOW);
        assertThatThrownBy(() -> running.ready(otherExecuted, NOW)).isInstanceOf(DomainException.class);
        assertThat(running.block(NOW).status()).isEqualTo(PaymentExecutionRequest.Status.BLOCKED);
    }
    @Test void voidedExpiredAndStaleLeaseCannotBecomeReadyFromLateChecks() {
        var running = queue().claim(NOW, LEASE); var executed = running.register(fixture.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), NOW);
        assertThat(running.ready(executed, running.leaseUntil()).status()).isEqualTo(PaymentExecutionRequest.Status.QUEUED);
        assertThatThrownBy(() -> running.register(fixture.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), running.leaseUntil())).isInstanceOf(DomainException.class);
        for (var stopped : List.of(running.voidSource(NOW), running.expireAuthorization(NOW))) {
            assertThat(stopped.active()).isFalse(); assertThatThrownBy(() -> stopped.ready(executed, NOW)).isInstanceOf(DomainException.class);
        }
    }
    @Test void readyCannotPrecedeActualExecutionRegistration() {
        var running = queue().claim(NOW, LEASE);
        var executed = running.register(fixture.authorization(), fixture.directory(), fixture.payee(), fixture.voucher(), NOW.plusSeconds(5));
        assertThatThrownBy(() -> running.ready(executed, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
    }
    private PaymentExecutionRequest queue() { return PaymentExecutionRequest.queue(UUID.randomUUID(), fixture.authorization(), "cashier", "debit-1", "v1", NOW); }
    private static Fixture fixture() {
        var command = VoucherCommandTest.advanceCommand(); var at = NOW.minusSeconds(2);
        var voucher = VoucherOperation.queue(new VoucherOperation.Input(command, "a".repeat(64)), at).claim(at, LEASE)
                .complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, NOW,
                        "posting-1", "voucher-1", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), NOW, null)), NOW);
        var payee = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(command.legalEntityId(), "alice", "payee-1", "****1234", "a".repeat(64), "v1"), NOW.plusSeconds(60));
        var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.legalEntityId(), "CNY", "cashier"), "v1", NOW, NOW.plusSeconds(60),
                List.of(new PaymentAccountsPort.DebitAccount("debit-1", "业务账户", "****5678", "CNY", "v1")));
        return new Fixture(PaymentAuthorization.issue(UUID.randomUUID(), voucher, payee.snapshot(), "finance", NOW, NOW.plusSeconds(3600)), voucher, directory, payee);
    }
    /**
     * 合成挂账、原授权和当前账户构成每项独立测试的来源。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(PaymentAuthorization authorization, VoucherOperation voucher, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account payee) { }
}
