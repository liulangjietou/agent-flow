package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.procurement.SupplierPayableSettlementTest.bank;
import static io.agentflow.procurement.SupplierPayableSettlementTest.command;
import static io.agentflow.procurement.SupplierPayableSettlementTest.evidence;
import static org.assertj.core.api.Assertions.*;

/**
 * 财务明确结算日期后，持久准备只复查原意图，读取故障或关闭期间不能产生结算命令。
 * @author owlzhangfq@gmail.com
 */
class SupplierSettlementPreparationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void initialIntentAndReadyCommandRetainActualBankRevisionFinanceAndChosenDate() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var reference = command(bank, now);
        var queued = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", reference.period().request().accountingDate(), now);
        assertThat(queued.version()).isEqualTo(1); assertThat(queued.attempts()).isZero(); assertThat(queued.active()).isTrue();
        var claimed = queued.claim(now, LEASE); var command = claimed.input().command(evidence(reference, now), now); var ready = claimed.ready(command, now);
        assertThat(command.id()).isEqualTo(queued.input().id()); assertThat(command.registeredFrom(bank)).isTrue();
        assertThat(ready.status()).isEqualTo(SupplierSettlementPreparation.Status.READY); assertThat(ready.active()).isFalse();
        assertThat(ready.input()).isSameAs(queued.input()); assertThat(ready.leaseUntil()).isNull();
    }

    @Test void applicantCashierUnconfirmedBankAndEarlierAccountingDateAreRejectedAtIntent() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var day = command(bank, now).period().request().accountingDate();
        for (String actor : new String[] { "alice", "cashier", " ", "finance\n" }) {
            assertThatThrownBy(() -> SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, actor, day, now)).isInstanceOf(DomainException.class);
        }
        var querying = bank.requestQuery(now).claim(now, LEASE);
        assertThatThrownBy(() -> SupplierSettlementPreparation.queue(UUID.randomUUID(), querying, "finance", day, now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", day.minusDays(1), now)).isInstanceOf(DomainException.class);
    }

    @Test void unavailableReadRetriesOriginalIntentAndExpiredReadCannotRegisterLateCommand() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var reference = command(bank, now);
        var claimed = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", reference.period().request().accountingDate(), now).claim(now, LEASE);
        var retry = claimed.unavailable(SupplierSettlementPreparation.Issue.CONNECTION, now.plusSeconds(1));
        assertThat(retry.status()).isEqualTo(SupplierSettlementPreparation.Status.QUEUED); assertThat(retry.nextAttemptAt()).isEqualTo(now.plusSeconds(6));
        assertThat(retry.input()).isSameAs(claimed.input()); assertThatThrownBy(() -> retry.claim(now.plusSeconds(5), LEASE)).isInstanceOf(DomainException.class);
        var next = retry.claim(retry.nextAttemptAt(), LEASE); assertThat(next.attempts()).isEqualTo(2);
        var late = claimed.leaseUntil(); var command = claimed.input().command(evidence(reference, late), late);
        assertThatThrownBy(() -> claimed.ready(command, late)).isInstanceOf(DomainException.class);
        assertThat(claimed.expireLease(late).status()).isEqualTo(SupplierSettlementPreparation.Status.QUEUED);
    }

    @Test void periodClosureAndSourceChangeStopOnlyPreparationAndCannotPretendRegistered() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var reference = command(bank, now);
        var queued = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", reference.period().request().accountingDate(), now);
        var blocked = queued.claim(now, LEASE).block(SupplierSettlementPreparation.Issue.ACCOUNTING_PERIOD_REJECTED, now);
        assertThat(blocked.status()).isEqualTo(SupplierSettlementPreparation.Status.BLOCKED); assertThat(blocked.active()).isFalse();
        assertThat(queued.voidSource(now).status()).isEqualTo(SupplierSettlementPreparation.Status.VOIDED);
        assertThat(bank.settleable()).isTrue(); assertThat(bank.command().held().status()).isEqualTo(SupplierPayableHoldObservation.Status.HELD);
        assertThatThrownBy(() -> blocked.claim(now, LEASE)).isInstanceOf(DomainException.class);
    }

    @Test void delayedEvidenceAndDifferentPeriodDateCannotChangeOriginalFinancialIntent() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var reference = command(bank, now); var correct = evidence(reference, now);
        var requested = now.plusSeconds(1); var intent = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", reference.period().request().accountingDate(), requested).input();
        assertThatThrownBy(() -> intent.command(correct, requested)).isInstanceOf(DomainException.class);
        var proof = evidence(reference, requested); var period = proof.period(); var wrong = new AccountingPeriodPort.OpenPeriod(
                new AccountingPeriodPort.Request(period.request().legalEntityId(), "CNY", period.request().accountingDate().plusDays(1)), period.periodReference(), period.sourceVersion(), period.startsOn(), period.endsOn(), requested, period.validUntil());
        var wrongDate = new SupplierPayableSettlementEvidence(proof.hold(), proof.paid(), wrong, requested);
        assertThatThrownBy(() -> intent.command(wrongDate, requested)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> intent.command(proof, proof.validUntil())).isInstanceOf(DomainException.class);
    }

    @Test void anotherPreparationIdOrFinanceCannotAttachItsCommandToThisReadyTransition() {
        var bank = bank(); var now = bank.updatedAt().plusSeconds(4); var reference = command(bank, now);
        var claimed = SupplierSettlementPreparation.queue(UUID.randomUUID(), bank, "finance", reference.period().request().accountingDate(), now).claim(now, LEASE);
        assertThatThrownBy(() -> claimed.ready(reference, now)).isInstanceOf(DomainException.class);
        var correct = claimed.input().command(evidence(reference, now), now);
        var other = new SupplierPayableSettlementCommand(correct.id(), correct.payment(), correct.paymentVersion(), correct.paid(), correct.period(), "finance-2", now);
        assertThatThrownBy(() -> claimed.ready(other, now)).isInstanceOf(DomainException.class);
    }
}
