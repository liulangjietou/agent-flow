package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 字段差异只应用本人选项，不能修改资金事实或绕过单行不变量。
 * @author owlzhangfq@gmail.com
 */
class ExpenseFieldPatchTest {
    private ExpenseContent content() {
        var gross = new Money(new BigDecimal("100.00"), "CNY");
        var line = new ExpenseLine(1, "OFFICE", LocalDate.of(2026, 10, 8), null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                gross, Money.zero("CNY"), List.of(UUID.randomUUID()), null, List.of(new CostAllocation("COST", null, gross)), "办公用品", null);
        return new ExpenseContent(UUID.randomUUID(), ExpenseContent.Type.DAILY, "测试费用", List.of(line), List.of());
    }
    @Test void selectedFieldsChangeWhileFinancialAndInvoiceFactsRemainIdentical() {
        var original = content(); var patch = new ExpenseFieldPatch(1, ExpenseFieldPatch.Field.DESCRIPTION, "办公用品", "办公室打印耗材", "仅补充用途，保存后仍须预检");
        var updated = ExpenseFieldPatch.apply(original, List.of(patch)); var before = original.lines().get(0); var after = updated.lines().get(0);
        assertThat(after.description()).isEqualTo("办公室打印耗材"); assertThat(before.description()).isEqualTo("办公用品");
        assertThat(after.claimedGross()).isEqualTo(before.claimedGross()); assertThat(after.claimedTax()).isEqualTo(before.claimedTax());
        assertThat(after.invoiceIds()).isEqualTo(before.invoiceIds()); assertThat(after.allocations()).isEqualTo(before.allocations());
        assertThat(after.categoryCode()).isEqualTo(before.categoryCode());
    }
    @Test void staleValueDuplicateFieldInvalidDateAndUnknownLineAreRejected() {
        var original = content(); var patch = new ExpenseFieldPatch(1, ExpenseFieldPatch.Field.DESCRIPTION, "旧的值", "新的值", "需要重检");
        assertThatThrownBy(() -> ExpenseFieldPatch.apply(original, List.of(patch))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExpenseFieldPatch.apply(original, List.of(patch, patch))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ExpenseFieldPatch(1, ExpenseFieldPatch.Field.INCURRED_ON, "2026-10-08", "2026-02-31", "需要重检")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ExpenseFieldPatch.apply(original, List.of(new ExpenseFieldPatch(2, ExpenseFieldPatch.Field.DESCRIPTION, "办公用品", "新值", "需要重检")))).isInstanceOf(DomainException.class);
    }
}
