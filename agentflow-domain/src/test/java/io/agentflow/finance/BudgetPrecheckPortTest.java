package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 预算基数来自核定含税分摊，不能改成税额、付款额或原币合计。
 * @author owlzhangfq@gmail.com
 */
class BudgetPrecheckPortTest {
    private static final UUID ENTITY = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 28);
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void requestUsesRoundedBaseAllocationsBeforeTaxAndAdvanceOffsets() {
        var line = new ExpenseLine(2, "TRAVEL", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
                money("0.03", "USD"), money("0.01", "USD"), List.of(), null,
                List.of(new CostAllocation("IT", "P1", money("0.01", "USD")), new CostAllocation("RESEARCH", null, money("0.02", "USD"))), "合成预算分摊", null);
        var report = ExpenseReport.draft(UUID.randomUUID(), "tenant", UUID.randomUUID(), "alice", new ExpenseContent(ENTITY,
                ExpenseContent.Type.TRAVEL, "合成预算", List.of(line), List.of(new AdvanceOffset(UUID.randomUUID(), money("0.03", "CNY")))));
        var rate = new ExpenseExchangeRate("USD", "CNY", new BigDecimal("1.5"), "synthetic-rate", DATE);
        var policy = new ExpensePolicySnapshot(UUID.randomUUID(), 1, money("0.05", "CNY"), money("0.05", "CNY"),
                ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic-tax", "synthetic-policy");
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "synthetic-account", "****1234", "a".repeat(64), "v1"),
                Map.of(2, new ExpenseAssessment(rate, policy, money("0.02", "CNY"))), "alice", NOW);
        var request = BudgetPrecheckPort.Request.from(report, DATE);
        assertThat(request.total()).isEqualTo(money("0.05", "CNY"));
        assertThat(report.currentRound().payable()).isEqualTo(money("0.02", "CNY"));
        assertThat(request.allocations()).containsExactly(
                new BudgetPrecheckPort.Allocation(2, 1, "TRAVEL", new CostAllocation("IT", "P1", money("0.02", "CNY"))),
                new BudgetPrecheckPort.Allocation(2, 2, "TRAVEL", new CostAllocation("RESEARCH", null, money("0.03", "CNY"))));
        assertThat(request.financialVersion()).isEqualTo(1); assertThat(request.reportId()).isEqualTo(report.id());
        var actual = BudgetPrecheckPort.Request.fromCurrent(report, DATE);
        assertThat(actual.financialVersion()).isEqualTo(2); assertThat(actual.allocations()).isEqualTo(request.allocations());
        assertThat(request.employeeId()).isEqualTo("alice"); assertThat(request.legalEntityId()).isEqualTo(ENTITY);
    }

    @Test
    void mixedCurrencyDuplicateAndUnboundedPositionsAreRejected() {
        var first = allocation(1, "CNY");
        assertThatThrownBy(() -> request(List.of(first, first))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> request(List.of(allocation(2, "USD")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> request(List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> allocation(51, "CNY")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetPrecheckPort.Allocation(201, 1, "TRAVEL", first.cost())).isInstanceOf(DomainException.class);
    }

    @Test
    void sourceCollectionsAndEvidenceCannotBeMutatedOrOmitted() {
        var mutable = new ArrayList<>(List.of(allocation(1, "CNY"))); var request = request(mutable); mutable.clear();
        assertThat(request.allocations()).hasSize(1);
        assertThatThrownBy(() -> request.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new BudgetPrecheckPort.Assessment(request, " ", NOW, NOW.plusSeconds(30))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new BudgetPrecheckPort.Assessment(request, "synthetic", NOW, NOW)).isInstanceOf(DomainException.class);
        assertThat(new BudgetPrecheckPort.Assessment(request, "synthetic", NOW, NOW.plusSeconds(30)).request()).isEqualTo(request);
    }

    private BudgetPrecheckPort.Request request(List<BudgetPrecheckPort.Allocation> allocations) {
        return new BudgetPrecheckPort.Request(UUID.randomUUID(), 2, 4, "alice", ENTITY, "CNY", DATE, allocations);
    }
    private BudgetPrecheckPort.Allocation allocation(int no, String currency) { return new BudgetPrecheckPort.Allocation(1, no, "TRAVEL", new CostAllocation("IT", null, money("10", currency))); }
    private Money money(String value, String currency) { return new Money(new BigDecimal(value), currency); }
}
