package io.agentflow.finance;

import io.agentflow.expense.InvoiceKey;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.procurement.ApprovedProcurementPayment;
import io.agentflow.procurement.ProcurementPayablePort;
import io.agentflow.procurement.ProcurementPayableReservation;
import io.agentflow.procurement.ProcurementPaymentContent;
import io.agentflow.procurement.ProcurementPaymentRequest;
import io.agentflow.procurement.SupplierAccountSnapshot;
import io.agentflow.procurement.SupplierPayableHoldCommand;
import io.agentflow.procurement.SupplierPayableHoldObservation;
import io.agentflow.procurement.SupplierPayableHoldOperation;
import io.agentflow.procurement.SupplierPaymentAuthorization;
import io.agentflow.procurement.SupplierPaymentCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 原核销与独立回款调整共用真实批准、预留及银行来源，保留大精度采购数量。
 * @author owlzhangfq@gmail.com
 */
final class SupplierFinanceProtocolFixture {
    private static final PaymentAccountsPort.DebitAccount DEBIT = new PaymentAccountsPort.DebitAccount("debit-1", "法人基本户", "****5678", "CNY", "v1");
    private SupplierFinanceProtocolFixture() { }
    static AccountingPeriodPort.OpenPeriod period(SupplierPaymentCommand payment, Instant now, LocalDate fixed) {
        var date = fixed == null ? now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate() : fixed;
        return new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(payment.payee().legalEntityId(), "CNY", date), "period-1", "v1", date.minusDays(30), date.plusDays(30), now, now.plusSeconds(600));
    }
    static PaymentObservation paid(SupplierPaymentCommand payment, Instant now) { return new PaymentObservation(payment.id(), payment.digest(), PaymentObservation.Status.SUCCEEDED, 1L, now, "bank-1", payment.amount(), payment.payee().accountDigest(), payment.registeredAt().plusSeconds(1), "receipt-1", null); }
    static SupplierPaymentCommand payment(String target, Instant now) {
        var entity = UUID.randomUUID(); var content = new ProcurementPaymentContent(entity, "采购付款", "已验收货物付款", "supplier-1", "payable-1", money("70"));
        var quantity = new BigDecimal("999999999999999.123456");
        var line = new ProcurementPayablePort.MatchedLine(1, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, "00000000000000000001"), 1,
                "c".repeat(64), "verification-1", "件", quantity, quantity, quantity, money("100"), money("100"), money("100"), money("6"));
        var payable = new ProcurementPayablePort.Payable(content.payableRequest("alice"), "v1", now, now.plusSeconds(600), "供应商",
                new SupplierAccountSnapshot(entity, "supplier-1", "private-supplier-account", "****1234", "a".repeat(64), "v1"),
                "contract-1", "order-1", "match-1", "accrual-1", "budget-1", LocalDate.parse("2026-10-01"), money("100"), money("30"), List.of(line));
        var request = ProcurementPaymentRequest.draft(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), "alice", content);
        var catalog = new FinanceCatalog("alice", "v1", now.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
        request.freeze(1, 1, catalog, target, payable, new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), now);
        var local = ProcurementPayableReservation.hold(UUID.randomUUID(), request, now); request.approve(2, 1, 8, "manager", now.plusSeconds(1));
        var holdCommand = new SupplierPayableHoldCommand(new SupplierPaymentAuthorization(UUID.randomUUID(), ApprovedProcurementPayment.from(request, local), payable, "finance", now.plusSeconds(2), now.plusSeconds(86400)));
        var original = SupplierPayableHoldOperation.queue(holdCommand, now.plusSeconds(2)).claim(now.plusSeconds(2), Duration.ofSeconds(30))
                .complete(new FinanceResult.Success<>(held(holdCommand, now.plusSeconds(4))), now.plusSeconds(4));
        return SupplierPaymentCommand.register(original, held(holdCommand, now.plusSeconds(5)), directory(holdCommand, now.plusSeconds(5)), DEBIT.reference(), "cashier", now.plusSeconds(5));
    }
    static PaymentAccountsPort.Directory directory(SupplierPayableHoldCommand command, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(command.authorization().payable().request().legalEntityId(), "CNY", "cashier"), "directory-v1", now, now.plusSeconds(600), List.of(DEBIT));
    }
    static SupplierPayableHoldObservation held(SupplierPayableHoldCommand command, Instant now) {
        return new SupplierPayableHoldObservation(command.id(), command.digest(), SupplierPayableHoldObservation.Status.HELD, 1L, now,
                "hold-1", "ledger-1", command.authorization().source().amount(), command.authorization().payable().account().accountDigest(), command.authorization().authorizedAt().plusSeconds(1), null);
    }
    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
}
