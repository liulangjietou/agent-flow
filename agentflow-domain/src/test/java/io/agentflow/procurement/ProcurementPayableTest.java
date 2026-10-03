package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 采购应付防止重复使用验收依据、错供应商账户及将原未付余额当作已经预留。
 * @author owlzhangfq@gmail.com
 */
class ProcurementPayableTest {
    static final UUID ENTITY = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test void matchedPayablePreservesExactInvoicesAndComputesActualOutstanding() {
        var list = new ArrayList<>(List.of(line(1, 1, "60", "6"), line(2, 2, "40", "4")));
        var payable = payable(list, "100", "30"); list.clear();
        assertThat(payable.lines()).hasSize(2); assertThat(payable.outstanding()).isEqualTo(money("70"));
        assertThat(payable.matches(request(), NOW)).isTrue();
        assertThat(payable.lines().get(0).invoice().canonical()).startsWith("D:00000000000000000001");
        assertThat(payable.toString()).doesNotContain("supplier-account", "供应商", "contract");
        assertThat(payable.account().toString()).doesNotContain("supplier-account", "supplier-1");
        assertThatThrownBy(() -> payable.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void individualLinesCannotInvoiceBeyondAcceptanceOrInventTaxAndQuantities() {
        for (String amount : List.of("100.01", "0")) {
            assertThatThrownBy(() -> line(1, 1, amount, "1")).isInstanceOf(DomainException.class);
        }
        for (String quantity : List.of("0", "-1", "10.000001", "0.0000001")) {
            assertThatThrownBy(() -> line(1, 1, "10", quantity)).isInstanceOf(DomainException.class);
        }
        var original = line(1, 1, "10", "1");
        assertThatThrownBy(() -> new ProcurementPayablePort.MatchedLine(1, 1, "receipt", original.invoice(), 1, "a".repeat(64), "verified", "件",
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, money("100"), money("100"), money("10"), money("10.01"))).isInstanceOf(DomainException.class);
    }

    @Test void repeatedOrderLinesCannotMultiplyAvailableAcceptanceOrChangeItsSource() {
        // 两张发票分别低于验收，但合计超出时仍必须整体拒绝。
        assertThatThrownBy(() -> payable(List.of(line(1, 1, "40", "6"), line(2, 2, "40", "6")), "80", "0")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payable(List.of(line(1, 1, "60", "1"), line(2, 2, "60", "1")), "120", "0")).isInstanceOf(DomainException.class);
        var source = line(2, 2, "40", "4");
        var changed = new ProcurementPayablePort.MatchedLine(2, 1, "another-receipt", source.invoice(), 2, source.invoiceDigest(), source.verificationReference(), source.unit(),
                source.orderedQuantity(), source.acceptedQuantity(), source.invoicedQuantity(), source.orderedGross(), source.acceptedGross(), source.invoicedGross(), source.tax());
        assertThatThrownBy(() -> payable(List.of(line(1, 1, "60", "6"), changed), "100", "0")).isInstanceOf(DomainException.class);
    }

    @Test void invoiceLinesGrossAndPaidAmountMustRemainConsistent() {
        var first = line(1, 1, "60", "6");
        assertThatThrownBy(() -> payable(List.of(first, line(2, 1, "40", "4")), "100", "0")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payable(List.of(first), "59.99", "0")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payable(List.of(first), "60", "60.01")).isInstanceOf(DomainException.class);
        assertThat(payable(List.of(first), "60", "60").outstanding()).isEqualTo(money("0"));
        var second = line(2, 2, "40", "4");
        var tampered = new ProcurementPayablePort.MatchedLine(2, 1, second.acceptanceReference(), first.invoice(), 2, "b".repeat(64), second.verificationReference(), second.unit(),
                second.orderedQuantity(), second.acceptedQuantity(), second.invoicedQuantity(), second.orderedGross(), second.acceptedGross(), second.invoicedGross(), second.tax());
        assertThatThrownBy(() -> payable(List.of(first, tampered), "100", "0")).isInstanceOf(DomainException.class);
    }

    @Test void evidenceExpiresExclusivelyAtFiveMinutesAndCannotBeRebound() {
        var payable = payable(List.of(line(1, 1, "100", "10")), "100", "0");
        assertThat(payable.matches(request(), NOW.minusNanos(1))).isFalse();
        assertThat(payable.matches(request(), NOW.plusSeconds(299))).isTrue();
        assertThat(payable.matches(request(), NOW.plusSeconds(300))).isFalse();
        assertThat(payable.matches(new ProcurementPayablePort.Request(ENTITY, "bob", "supplier-1", "payable-1"), NOW)).isFalse();
        assertThat(payable.matches(new ProcurementPayablePort.Request(ENTITY, "alice", "supplier-2", "payable-1"), NOW)).isFalse();
    }

    @Test void supplierAccountMustBeMaskedAndBoundToTheOriginalSupplier() {
        assertThatThrownBy(() -> new SupplierAccountSnapshot(ENTITY, "supplier-1", "bank", "621234567890", "a".repeat(64), "v1")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new SupplierAccountSnapshot(ENTITY, "supplier-1\n", "bank", "****1234", "a".repeat(64), "v1")).isInstanceOf(DomainException.class);
        var source = payable(List.of(line(1, 1, "100", "10")), "100", "0");
        var other = new SupplierAccountSnapshot(ENTITY, "supplier-2", "bank", "****1234", "a".repeat(64), "v1");
        assertThatThrownBy(() -> new ProcurementPayablePort.Payable(source.request(), "v1", NOW, NOW.plusSeconds(600), source.supplierName(), other,
                source.contractReference(), source.orderReference(), source.matchingReference(), source.accrualVoucherReference(), source.budgetRecognitionReference(),
                source.dueOn(), source.gross(), source.settled(), source.lines())).isInstanceOf(DomainException.class);
    }

    static ProcurementPayablePort.Request request() { return new ProcurementPayablePort.Request(ENTITY, "alice", "supplier-1", "payable-1"); }
    static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    static SupplierAccountSnapshot account() { return new SupplierAccountSnapshot(ENTITY, "supplier-1", "supplier-account", "****1234", "a".repeat(64), "v1"); }
    static ProcurementPayablePort.Payable payable(List<ProcurementPayablePort.MatchedLine> lines, String gross, String paid) {
        return new ProcurementPayablePort.Payable(request(), "v1", NOW, NOW.plusSeconds(600), "供应商", account(), "contract-1", "order-1", "match-1", "voucher-1", "budget-1",
                LocalDate.parse("2026-10-01"), money(gross), money(paid), lines);
    }
    static ProcurementPayablePort.MatchedLine line(int line, int invoice, String amount, String quantity) {
        return new ProcurementPayablePort.MatchedLine(line, 1, "receipt-1", new InvoiceKey(InvoiceKey.Type.DIGITAL, null, String.format("%020d", invoice)), 1,
                "a".repeat(64), "verified-1", "件", BigDecimal.TEN, BigDecimal.TEN, new BigDecimal(quantity), money("100"), money("100"), money(amount), money("0"));
    }
}
