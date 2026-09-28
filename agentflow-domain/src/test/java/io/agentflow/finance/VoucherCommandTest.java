package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 用会计用途及原交易证据验证凭证，单纯借贷平衡不足以证明正确入账。
 * @author owlzhangfq@gmail.com
 */
class VoucherCommandTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BUSINESS = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID APPLICATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ENTITY = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final VoucherCommand.Binding BINDING = new VoucherCommand.Binding(BUSINESS, APPLICATION, 2, 7, 4);

    @Test
    void advancePostingBindsImmutableLinesAndEveryFinancialVersionToDigest() {
        var lines = new ArrayList<>(advanceLines()); var command = command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), lines, null);
        lines.clear(); assertThat(command.lines()).hasSize(2);
        // Python hashlib 与 struct 大端字节长度编码独立生成，包含中文科目。
        assertThat(command.digest()).isEqualTo("99de112e40fd24e5c91b5aaac01d92e0b306b1e874f97db515bb5828dbac8b80");
        assertThat(command.toString()).doesNotContain("alice", "1122", "100.00");
        var entries = command.mapping().entries().stream().map(entry -> new AccountMappingPort.Entry(entry.key(), entry.accountCode() + "-v2")).toList();
        var changedMapping = new AccountMappingPort.Mapping(command.mapping().request(), "map-v2", command.mapping().observedAt(), command.mapping().validUntil(), entries);
        assertThat(copy(command, command.binding(), command.period(), changedMapping, command.lines(), command.expiresAt()).digest()).isNotEqualTo(command.digest());
        assertThat(copy(command, new VoucherCommand.Binding(BUSINESS, APPLICATION, 2, 8, 4), command.period(), command.mapping(), command.lines(), command.expiresAt()).digest()).isNotEqualTo(command.digest());
        assertThat(copy(command, command.binding(), command.period(), command.mapping(), command.lines(), NOW.plusSeconds(59)).digest()).isNotEqualTo(command.digest());
        var reversedOrder = new ArrayList<>(command.lines()); java.util.Collections.reverse(reversedOrder);
        assertThat(copy(command, command.binding(), command.period(), command.mapping(), reversedOrder, command.expiresAt()).digest()).isEqualTo(command.digest());
    }

    @Test
    void balancedButWrongDirectionsWrongTaxAndRepeatedOffsetsAreRejected() {
        var good = command(VoucherCommand.Kind.EXPENSE_ACCRUAL, totals("6", "20"), expenseLines("94", "6", "20", "80"), null);
        assertThat(good.totals().payable()).isEqualTo(money("80"));
        var wrongDirection = good.lines().stream().map(line -> new VoucherCommand.Line(line.lineNo(), line.account(), line.side() == VoucherCommand.Side.DEBIT ? VoucherCommand.Side.CREDIT : VoucherCommand.Side.DEBIT,
                line.amount(), line.sourceLineNo(), line.costCenter(), line.projectCode(), line.advanceId())).toList();
        assertThatThrownBy(() -> copy(good, good.binding(), good.period(), good.mapping(), wrongDirection, good.expiresAt())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> command(VoucherCommand.Kind.EXPENSE_ACCRUAL, totals("6", "20"), expenseLines("95", "5", "20", "80"), null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> command(VoucherCommand.Kind.EXPENSE_ACCRUAL, totals("6", "20"), expenseLines("94", "6", "21", "79"), null)).isInstanceOf(DomainException.class);
        var missingBorrowing = new ArrayList<>(good.lines()); var offset = missingBorrowing.get(2);
        missingBorrowing.set(2, new VoucherCommand.Line(3, offset.account(), offset.side(), offset.amount(), 0, null, null, null));
        assertThatThrownBy(() -> copy(good, good.binding(), good.period(), good.mapping(), missingBorrowing, good.expiresAt())).isInstanceOf(DomainException.class);
        var duplicates = new ArrayList<>(good.lines()); duplicates.add(new VoucherCommand.Line(5, offset.account(), offset.side(), money("1"), 0, null, null, offset.advanceId()));
        assertThatThrownBy(() -> copy(good, good.binding(), good.period(), good.mapping(), duplicates, good.expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test
    void fullAdvanceOffsetProducesBalancedAccrualWithoutPayableLine() {
        var lines = expenseLines("94", "6", "100", "0").subList(0, 3);
        var command = command(VoucherCommand.Kind.EXPENSE_ACCRUAL, totals("6", "100"), lines, null);
        assertThat(command.totals().payable()).isEqualTo(money("0"));
        assertThat(command.lines()).noneMatch(line -> line.account().role() == AccountMappingPort.Role.EMPLOYEE_PAYABLE);
    }

    @Test
    void missingAuxiliaryDimensionsCurrencyMixingAndUnbalancedOrZeroLinesFailClosed() {
        assertThatThrownBy(() -> new VoucherCommand.Line(1, key(AccountMappingPort.Role.EXPENSE), VoucherCommand.Side.DEBIT, money("1"), 1, null, null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherCommand.Line(1, key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), VoucherCommand.Side.CREDIT, money("1"), 1, "IT", null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherCommand.Line(1, key(AccountMappingPort.Role.EXPENSE), VoucherCommand.Side.DEBIT, money("0"), 1, "IT", null, null)).isInstanceOf(DomainException.class);
        var lines = new ArrayList<>(advanceLines());
        lines.set(1, new VoucherCommand.Line(2, key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), VoucherCommand.Side.CREDIT, new Money(new BigDecimal("100"), "USD"), 0, null, null, null));
        assertThatThrownBy(() -> command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), lines, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> command(VoucherCommand.Kind.EXPENSE_ACCRUAL, totals("6", "20"), expenseLines("94", "6", "20", "81"), null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherCommand.Totals(money("0"), money("0"), money("0"))).isInstanceOf(DomainException.class);
    }

    @Test
    void originalPeriodAndMappingMustCoverCreationAndSendingWindow() {
        var good = command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), advanceLines(), null);
        assertThatCode(() -> good.requireSendAt(NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> good.requireSendAt(NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> good.requireSendAt(NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> copy(good, good.binding(), good.period(), good.mapping(), good.lines(), NOW.plusSeconds(301))).isInstanceOf(DomainException.class);
        var otherDate = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(ENTITY, "CNY", DATE.plusDays(1)), "2026-09", "period-v1", DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), NOW.minusSeconds(60), NOW.plusSeconds(300));
        assertThatThrownBy(() -> copy(good, good.binding(), otherDate, good.mapping(), good.lines(), good.expiresAt())).isInstanceOf(DomainException.class);
        var missing = new AccountMappingPort.Mapping(new AccountMappingPort.Request(ENTITY, "CNY", List.of(key(AccountMappingPort.Role.EMPLOYEE_PAYABLE))), "map-v1", NOW.minusSeconds(60), NOW.plusSeconds(300),
                List.of(new AccountMappingPort.Entry(key(AccountMappingPort.Role.EMPLOYEE_PAYABLE), "2241")));
        assertThatThrownBy(() -> copy(good, good.binding(), good.period(), missing, good.lines(), good.expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test
    void paymentVoucherRequiresSuccessfulOriginalPaymentAndSameDebitAccountBinding() {
        var payment = payment(); var paid = paid(payment); var proof = new VoucherCommand.PaymentProof(payment, paid);
        var lines = List.of(line(1, AccountMappingPort.Role.EMPLOYEE_PAYABLE, "100", VoucherCommand.Side.DEBIT), line(2, AccountMappingPort.Role.BANK, "100", VoucherCommand.Side.CREDIT));
        var voucher = command(VoucherCommand.Kind.PAYMENT, totals("0", "0"), lines, proof);
        assertThat(voucher.payment().command().id()).isEqualTo(payment.id());
        assertThatThrownBy(() -> command(VoucherCommand.Kind.PAYMENT, totals("0", "0"), lines, null)).isInstanceOf(DomainException.class);
        var pending = new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.PENDING, 1L, NOW, "bank-1", null, null, null, null, null);
        assertThatThrownBy(() -> new VoucherCommand.PaymentProof(payment, pending)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> copy(voucher, new VoucherCommand.Binding(BUSINESS, APPLICATION, 3, 7, 4), voucher.period(), voucher.mapping(), lines, voucher.expiresAt())).isInstanceOf(DomainException.class);
        var changedBank = List.of(lines.get(0), new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "other-bank"), VoucherCommand.Side.CREDIT, money("100"), 0, null, null, null));
        assertThatThrownBy(() -> command(VoucherCommand.Kind.PAYMENT, totals("0", "0"), changedBank, proof)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), advanceLines(), proof)).isInstanceOf(DomainException.class);
    }

    @Test
    void paymentProofCannotBeReusedByAnotherTenantWithMatchingBusinessIdentifiers() {
        var payment = payment();
        var voucher = command(VoucherCommand.Kind.PAYMENT, totals("0", "0"), List.of(line(1, AccountMappingPort.Role.EMPLOYEE_PAYABLE, "100", VoucherCommand.Side.DEBIT),
                line(2, AccountMappingPort.Role.BANK, "100", VoucherCommand.Side.CREDIT)), new VoucherCommand.PaymentProof(payment, paid(payment)));
        assertThatThrownBy(() -> new VoucherCommand(voucher.id(), "tenant-b", voucher.kind(), voucher.binding(), voucher.legalEntityId(), voucher.employeeId(),
                voucher.accountingDate(), voucher.totals(), voucher.period(), voucher.mapping(), voucher.lines(), voucher.payment(), voucher.createdAt(), voucher.expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test
    void postedFactRequiresOriginalAmountPeriodAndDateButQueriesSurviveEvidenceExpiry() {
        var command = command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), advanceLines(), null);
        var posted = posted(command, VoucherObservation.Status.POSTED, "2026-09", DATE, money("100"), NOW.plusSeconds(3600));
        assertThat(posted.matches(command, true, NOW.plusSeconds(3600))).isTrue();
        assertThat(posted.matches(command, true, NOW)).isFalse();
        assertThat(posted(command, VoucherObservation.Status.POSTED, "2026-10", DATE, money("100"), NOW).matches(command, true, NOW)).isFalse();
        assertThat(posted(command, VoucherObservation.Status.POSTED, "2026-09", DATE.plusDays(1), money("100"), NOW).matches(command, true, NOW)).isFalse();
        assertThat(posted(command, VoucherObservation.Status.POSTED, "2026-09", DATE, money("99"), NOW).matches(command, true, NOW)).isFalse();
        var missing = new VoucherObservation(ID, command.digest(), VoucherObservation.Status.NOT_FOUND, 0L, NOW, null, null, null, null, null, null, null, null);
        assertThat(missing.matches(command, false, NOW)).isFalse(); assertThat(missing.matches(command, true, NOW)).isTrue();
        var reversed = posted(command, VoucherObservation.Status.REVERSED, "2026-09", DATE, money("100"), NOW);
        assertThat(reversed.matches(command, true, NOW)).isTrue(); assertThat(reversed.status()).isNotEqualTo(VoucherObservation.Status.POSTED);
        assertThatThrownBy(() -> new VoucherObservation(ID, command.digest(), VoucherObservation.Status.POSTED, 1L, NOW, "erp-1", "voucher-1", "2026-09", DATE, money("100"), money("99"), NOW, null)).isInstanceOf(DomainException.class);
    }

    private static VoucherCommand command(VoucherCommand.Kind kind, VoucherCommand.Totals totals, List<VoucherCommand.Line> lines, VoucherCommand.PaymentProof proof) {
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(ENTITY, "CNY", DATE), "2026-09", "period-v1", DATE.withDayOfMonth(1), DATE.withDayOfMonth(30), NOW.minusSeconds(60), NOW.plusSeconds(300));
        var request = new AccountMappingPort.Request(ENTITY, "CNY", lines.stream().map(VoucherCommand.Line::account).distinct().toList());
        var mapping = new AccountMappingPort.Mapping(request, "map-v1", NOW.minusSeconds(60), NOW.plusSeconds(300), request.keys().stream()
                .map(key -> new AccountMappingPort.Entry(key, key.role() == AccountMappingPort.Role.EMPLOYEE_RECEIVABLE ? "1122-员工" : "2241-员工")).toList());
        return new VoucherCommand(ID, "tenant-a", kind, BINDING, ENTITY, "alice", DATE, totals, period, mapping, lines, proof, NOW, NOW.plusSeconds(60));
    }
    static VoucherCommand advanceCommand() { return command(VoucherCommand.Kind.EMPLOYEE_ADVANCE, totals("0", "0"), advanceLines(), null); }
    private static VoucherCommand copy(VoucherCommand command, VoucherCommand.Binding binding, AccountingPeriodPort.OpenPeriod period, AccountMappingPort.Mapping mapping, List<VoucherCommand.Line> lines, Instant expiresAt) {
        return new VoucherCommand(command.id(), command.tenantId(), command.kind(), binding, command.legalEntityId(), command.employeeId(), command.accountingDate(), command.totals(), period, mapping, lines, command.payment(), NOW, expiresAt);
    }
    private static List<VoucherCommand.Line> advanceLines() { return List.of(line(1, AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, "100", VoucherCommand.Side.DEBIT), line(2, AccountMappingPort.Role.EMPLOYEE_PAYABLE, "100", VoucherCommand.Side.CREDIT)); }
    private static List<VoucherCommand.Line> expenseLines(String expense, String tax, String offset, String payable) {
        var lines = new ArrayList<>(List.of(line(1, AccountMappingPort.Role.EXPENSE, expense, VoucherCommand.Side.DEBIT), line(2, AccountMappingPort.Role.DEDUCTIBLE_TAX, tax, VoucherCommand.Side.DEBIT), line(3, AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, offset, VoucherCommand.Side.CREDIT)));
        if (!payable.equals("0")) lines.add(line(4, AccountMappingPort.Role.EMPLOYEE_PAYABLE, payable, VoucherCommand.Side.CREDIT));
        return lines;
    }
    private static VoucherCommand.Line line(int number, AccountMappingPort.Role role, String amount, VoucherCommand.Side side) {
        boolean expense = role == AccountMappingPort.Role.EXPENSE || role == AccountMappingPort.Role.DEDUCTIBLE_TAX;
        return new VoucherCommand.Line(number, key(role), side, money(amount), expense ? 1 : 0, expense ? "IT" : null, null, role == AccountMappingPort.Role.EMPLOYEE_RECEIVABLE ? BUSINESS : null);
    }
    private static AccountMappingPort.Key key(AccountMappingPort.Role role) { return new AccountMappingPort.Key(role, role == AccountMappingPort.Role.EXPENSE ? "OFFICE" : role == AccountMappingPort.Role.BANK ? "debit-1" : ""); }
    private static VoucherCommand.Totals totals(String tax, String offset) { return new VoucherCommand.Totals(money("100"), money(tax), money(offset)); }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static PaymentCommand payment() {
        return new PaymentCommand(UUID.randomUUID(), "tenant-a", PaymentCommand.Purpose.EMPLOYEE_ADVANCE, new PaymentCommand.Binding(BUSINESS, APPLICATION, 2, 7, 4), money("100"), "debit-1",
                new EmployeeAccountSnapshot(ENTITY, "alice", "payee-1", "****1234", "a".repeat(64), "v1"), "advance-voucher-1", new PaymentCommand.Authorization("finance", "cashier", NOW.minusSeconds(60), NOW.plusSeconds(60)));
    }
    private static PaymentObservation paid(PaymentCommand command) { return new PaymentObservation(command.id(), command.digest(), PaymentObservation.Status.SUCCEEDED, 2L, NOW, "bank-1", command.amount(), command.payee().accountDigest(), NOW, "receipt-1", null); }
    private static VoucherObservation posted(VoucherCommand command, VoucherObservation.Status status, String period, LocalDate date, Money amount, Instant now) {
        return new VoucherObservation(command.id(), command.digest(), status, 2L, now, "posting-1", "voucher-1", period, date, amount, amount, now, null);
    }
}
