package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 人工付款授权绑定真实挂账净应付，一次执行固定出纳，恢复时仍复核原财务事实。
 * @author owlzhangfq@gmail.com
 */
class PaymentAuthorizationTest {
    private static final Instant BASE = Instant.parse("2026-09-28T12:00:00Z");
    private static final Instant NOW = BASE.plusSeconds(2);
    private final VoucherOperation posted = posted(VoucherCommandTest.advanceCommand());
    private final EmployeeAccountSnapshot payee = new EmployeeAccountSnapshot(posted.input().command().legalEntityId(), "alice", "payee-1", "****1234", "a".repeat(64), "v1");

    @Test
    void dueDateIsAnImmutableDecisionAndCannotChangeCommandDigestOrExecutionWindow() {
        UUID id = UUID.randomUUID(); var expiry = NOW.plusSeconds(60);
        var legacy = PaymentAuthorization.issue(id, posted, payee, "finance", NOW, expiry);
        var dated = PaymentAuthorization.issue(id, posted, payee, "finance", NOW, expiry, LocalDate.of(2026, 10, 1));
        var directory = directory("cashier", expiry); var account = currentPayee(expiry);
        var oldExecution = execute(legacy, "cashier", directory, account, posted, NOW);
        var newExecution = execute(dated, "cashier", directory, account, posted, NOW);
        assertThat(newExecution.execution().command()).isEqualTo(oldExecution.execution().command());
        assertThat(newExecution.execution().command().digest()).isEqualTo(oldExecution.execution().command().digest());
        assertThat(newExecution.decision()).isSameAs(dated.decision());
        assertThat(dated.voidBeforeExecution("finance", "复核原授权", NOW).decision()).isSameAs(dated.decision());
        assertThat(dated.expire(expiry).decision()).isSameAs(dated.decision());
        assertThatThrownBy(() -> execute(dated, "cashier", directory("cashier", expiry.plusSeconds(60)), currentPayee(expiry.plusSeconds(60)), posted, expiry))
                .isInstanceOf(DomainException.class).hasMessage("Payment authorization is outside its execution window");
        for (var date : List.of(PaymentAuthorization.MIN_DUE_DATE, LocalDate.of(2020, 2, 29), PaymentAuthorization.MAX_DUE_DATE)) {
            assertThat(new PaymentAuthorization.Decision("finance", NOW, expiry, date).dueDate()).isEqualTo(date);
        }
        for (var date : List.of(LocalDate.of(0, 1, 1), LocalDate.of(10000, 1, 1))) {
            assertThatThrownBy(() -> new PaymentAuthorization.Decision("finance", NOW, expiry, date)).isInstanceOf(DomainException.class);
        }
        assertThat(legacy.decision().dueDate()).isNull();
    }

    @Test
    void derivesAdvanceAndNetExpenseAmountsFromPostedVoucherWithoutTreatingZeroPayableAsPayment() {
        var advance = issue(posted); assertThat(advance.terms().amount()).isEqualTo(money("100"));
        assertThat(advance.terms().purpose()).isEqualTo(PaymentCommand.Purpose.EMPLOYEE_ADVANCE);
        var expense = issue(posted(expense("40"))); assertThat(expense.terms().amount()).isEqualTo(money("60"));
        assertThat(expense.terms().purpose()).isEqualTo(PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT);
        assertThat(advance.terms().binding().applicationVersion()).isEqualTo(posted.input().command().binding().applicationVersion());
        assertThat(advance.terms().voucherCommandDigest()).isEqualTo(posted.input().command().digest()); assertThat(advance.terms().targetDigest()).isEqualTo(posted.input().targetDigest());
        assertThat(advance.execution()).isNull(); assertThat(advance.status()).isEqualTo(PaymentAuthorization.Status.AUTHORIZED);
        assertThatThrownBy(() -> issue(posted(expense("100")))).isInstanceOf(DomainException.class).hasMessage("Zero payable settlements do not create payment authorizations");
    }

    @Test
    void unpostedQueryingReversedAndFutureFactsCannotAuthorizeAndApplicantCannotAuthorizeSelf() {
        var queued = VoucherOperation.queue(posted.input(), BASE);
        var query = posted.requestQuery(NOW).claim(NOW, Duration.ofSeconds(15));
        var reversed = query.complete(new FinanceResult.Success<>(observation(posted.input().command(), VoucherObservation.Status.REVERSED, 2, NOW.plusSeconds(1))), NOW.plusSeconds(1));
        for (var value : List.of(queued, query, reversed)) assertThatThrownBy(() -> issue(value)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentAuthorization.issue(UUID.randomUUID(), posted, payee, "finance", BASE, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PaymentAuthorization.issue(UUID.randomUUID(), posted, payee, "alice", NOW, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        var other = new EmployeeAccountSnapshot(payee.legalEntityId(), "bob", "payee-2", "****5678", "b".repeat(64), "v1");
        assertThatThrownBy(() -> PaymentAuthorization.issue(UUID.randomUUID(), posted, other, "finance", NOW, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
    }

    @Test
    void executionFixesOriginalAuthorizationIdentityAccountEvidenceAndDistinctCashierOnlyOnce() {
        var authorization = issue(posted); var executed = execute(authorization, "cashier", directory("cashier", NOW.plusSeconds(90)), currentPayee(NOW.plusSeconds(30)), posted, NOW);
        assertThat(executed.status()).isEqualTo(PaymentAuthorization.Status.EXECUTION_REGISTERED); assertThat(executed.version()).isEqualTo(2);
        var command = executed.execution().command(); assertThat(command.id()).isEqualTo(authorization.terms().id()); assertThat(command.amount()).isEqualTo(money("100"));
        assertThat(command.binding()).isEqualTo(authorization.terms().binding()); assertThat(command.payee()).isEqualTo(payee);
        assertThat(command.authorization().authorizedBy()).isEqualTo("finance"); assertThat(command.authorization().executedBy()).isEqualTo("cashier");
        assertThat(command.authorization().expiresAt()).isEqualTo(authorization.decision().expiresAt());
        assertThat(executed.execution().accountsValidUntil()).isEqualTo(NOW.plusSeconds(30)); assertThat(command.debitAccountReference()).isEqualTo("debit-1");
        assertThatThrownBy(() -> execute(executed, "cashier-2", directory("cashier-2", NOW.plusSeconds(90)), currentPayee(NOW.plusSeconds(30)), posted, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> executed.voidBeforeExecution("finance", "不能撤销可能已受理的付款", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> executed.expire(authorization.decision().expiresAt())).isInstanceOf(DomainException.class);
        assertThat(executed.toString()).doesNotContain("payee-1", "debit-1", "alice", "100.00"); assertThat(executed.terms().toString()).doesNotContain("payee-1", "alice");
    }

    @Test
    void executionRejectsApplicantAuthorizerForeignCashierDirectoryAndChangedPayee() {
        var authorization = issue(posted);
        for (String actor : List.of("alice", "finance")) assertThatThrownBy(() -> execute(authorization, actor, directory(actor, NOW.plusSeconds(60)), currentPayee(NOW.plusSeconds(60)), posted, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> execute(authorization, "cashier", directory("other", NOW.plusSeconds(60)), currentPayee(NOW.plusSeconds(60)), posted, NOW)).isInstanceOf(DomainException.class);
        var changed = new EmployeeAccountPort.Account(new EmployeeAccountSnapshot(payee.legalEntityId(), "alice", "new-payee", "****5678", "b".repeat(64), "v2"), NOW.plusSeconds(60));
        assertThatThrownBy(() -> execute(authorization, "cashier", directory("cashier", NOW.plusSeconds(60)), changed, posted, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorization.registerExecution("cashier", directory("cashier", NOW.plusSeconds(60)), "unlisted-account", currentPayee(NOW.plusSeconds(60)), posted, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void staleAccountEvidenceAndQueryingVoucherStopExecutionWithoutAlteringOriginalAuthorization() {
        var authorization = issue(posted); var directory = directory("cashier", NOW.plusSeconds(1));
        assertThatThrownBy(() -> execute(authorization, "cashier", directory, currentPayee(NOW.plusSeconds(60)), posted, NOW.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> execute(authorization, "cashier", directory, currentPayee(NOW), posted, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> execute(authorization, "cashier", directory, currentPayee(NOW.plusSeconds(60)), posted.requestQuery(NOW), NOW)).isInstanceOf(DomainException.class);
        assertThat(authorization.status()).isEqualTo(PaymentAuthorization.Status.AUTHORIZED); assertThat(authorization.execution()).isNull();
    }

    @Test
    void originalExpiryCannotBeExtendedAndTerminalAuthorizationCannotExecute() {
        var authorization = issue(posted); var until = authorization.decision().expiresAt();
        assertThatThrownBy(() -> execute(authorization, "cashier", directory("cashier", until.plusSeconds(60)), currentPayee(until.plusSeconds(60)), posted, until)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorization.expire(until.minusNanos(1))).isInstanceOf(DomainException.class);
        var expired = authorization.expire(until); assertThat(expired.status()).isEqualTo(PaymentAuthorization.Status.EXPIRED);
        assertThatThrownBy(() -> expired.voidBeforeExecution("finance", "不可复活", until)).isInstanceOf(DomainException.class);
        var voided = authorization.voidBeforeExecution("finance", " 原依据需要复核 ", NOW); assertThat(voided.withdrawal().reason()).isEqualTo("原依据需要复核");
        assertThatThrownBy(() -> execute(voided, "cashier", directory("cashier", until), currentPayee(until), posted, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorization.voidBeforeExecution("alice", "本人不能作废授权", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PaymentAuthorization.Decision("finance", NOW, NOW.plus(PaymentAuthorization.MAX_VALIDITY).plusNanos(1))).isInstanceOf(DomainException.class);
        assertThatCode(() -> new PaymentAuthorization.Decision("finance", NOW, NOW.plus(PaymentAuthorization.MAX_VALIDITY))).doesNotThrowAnyException();
    }

    @Test
    void changedStoredTermsCannotUseAnOtherwiseMatchingVoucherToCreateAnotherAmountOrBusinessPayment() {
        var original = issue(posted); var terms = original.terms();
        for (var changed : List.of(
                new PaymentAuthorization.Terms(terms.id(), terms.tenantId(), terms.purpose(), terms.binding(), money("999"), terms.payee(), terms.voucherOperationId(), terms.voucherCommandDigest(), terms.voucherRevision(), terms.voucherReference(), terms.targetDigest()),
                new PaymentAuthorization.Terms(terms.id(), terms.tenantId(), terms.purpose(), new PaymentCommand.Binding(UUID.randomUUID(), terms.binding().applicationId(), terms.binding().roundNo(), terms.binding().applicationVersion(), terms.binding().businessVersion()), terms.amount(), terms.payee(), terms.voucherOperationId(), terms.voucherCommandDigest(), terms.voucherRevision(), terms.voucherReference(), terms.targetDigest()),
                new PaymentAuthorization.Terms(terms.id(), "another-tenant", terms.purpose(), terms.binding(), terms.amount(), terms.payee(), terms.voucherOperationId(), terms.voucherCommandDigest(), terms.voucherRevision(), terms.voucherReference(), terms.targetDigest()))) {
            var restored = new PaymentAuthorization(changed, original.decision(), 1, PaymentAuthorization.Status.AUTHORIZED, NOW, null, null);
            assertThat(restored.matchesVoucher(posted, NOW)).isFalse();
            assertThatThrownBy(() -> execute(restored, "cashier", directory("cashier", NOW.plusSeconds(60)), currentPayee(NOW.plusSeconds(60)), posted, NOW)).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void restoredExecutionCannotChangeFixedCommandOrBecomeASecondReadyAuthorization() {
        var original = issue(posted);
        var executed = execute(original, "cashier", directory("cashier", NOW.plusSeconds(60)), currentPayee(NOW.plusSeconds(60)), posted, NOW);
        assertThatThrownBy(() -> new PaymentAuthorization(executed.terms(), executed.decision(), 2, PaymentAuthorization.Status.AUTHORIZED, NOW, null, null)).isInstanceOf(DomainException.class);
        var command = executed.execution().command();
        var changedCommand = new PaymentCommand(command.id(), command.tenantId(), command.purpose(), command.binding(), money("999"), command.debitAccountReference(), command.payee(), command.voucherReference(), command.authorization());
        var changed = new PaymentAuthorization.Execution(changedCommand, executed.execution().debitAccount(), NOW, NOW.plusSeconds(60));
        assertThatThrownBy(() -> new PaymentAuthorization(executed.terms(), executed.decision(), 2, PaymentAuthorization.Status.EXECUTION_REGISTERED, NOW, changed, null)).isInstanceOf(DomainException.class);
    }

    private PaymentAuthorization issue(VoucherOperation voucher) { return PaymentAuthorization.issue(UUID.randomUUID(), voucher, payee, "finance", NOW, NOW.plusSeconds(3600)); }
    private PaymentAuthorization execute(PaymentAuthorization value, String cashier, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account account, VoucherOperation voucher, Instant now) {
        return value.registerExecution(cashier, directory, "debit-1", account, voucher, now);
    }
    private EmployeeAccountPort.Account currentPayee(Instant until) { return new EmployeeAccountPort.Account(payee, until); }
    private PaymentAccountsPort.Directory directory(String cashier, Instant until) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(payee.legalEntityId(), "CNY", cashier), "v1", NOW.minusSeconds(1), until,
                List.of(new PaymentAccountsPort.DebitAccount("debit-1", "业务账户", "****5678", "CNY", "v1")));
    }
    private static VoucherOperation posted(VoucherCommand command) {
        var claimed = VoucherOperation.queue(new VoucherOperation.Input(command, "a".repeat(64)), BASE).claim(BASE, Duration.ofSeconds(15));
        return claimed.complete(new FinanceResult.Success<>(observation(command, VoucherObservation.Status.POSTED, 1, BASE.plusSeconds(1))), BASE.plusSeconds(1));
    }
    private static VoucherObservation observation(VoucherCommand command, VoucherObservation.Status status, long revision, Instant at) {
        return new VoucherObservation(command.id(), command.digest(), status, revision, at, "posting-1", "voucher-1", command.period().periodReference(), command.accountingDate(), command.totals().gross(), command.totals().gross(), BASE.plusSeconds(1), null);
    }
    private static VoucherCommand expense(String offset) {
        var original = VoucherCommandTest.advanceCommand(); var totals = new VoucherCommand.Totals(money("100"), money("0"), money(offset));
        var lines = new ArrayList<VoucherCommand.Line>();
        lines.add(new VoucherCommand.Line(1, new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE"), VoucherCommand.Side.DEBIT, money("100"), 1, "IT", null, null));
        lines.add(new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), VoucherCommand.Side.CREDIT, money(offset), 0, null, null, UUID.randomUUID()));
        if (totals.payable().value().signum() > 0) lines.add(new VoucherCommand.Line(3, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), VoucherCommand.Side.CREDIT, totals.payable(), 0, null, null, null));
        var mappingRequest = new AccountMappingPort.Request(original.legalEntityId(), "CNY", lines.stream().map(VoucherCommand.Line::account).toList());
        var mapping = new AccountMappingPort.Mapping(mappingRequest, "v1", BASE.minusSeconds(1), BASE.plusSeconds(60), mappingRequest.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
        return new VoucherCommand(original.id(), original.tenantId(), VoucherCommand.Kind.EXPENSE_ACCRUAL, original.binding(), original.legalEntityId(), original.employeeId(), original.accountingDate(), totals, original.period(), mapping, lines, null, BASE, BASE.plusSeconds(60));
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
