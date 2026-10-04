package io.agentflow.expense;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.common.JsonUtil;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用服务端真实金额和时间模块验证原预检协议，不在领域测试中模拟持久编码。
 * @author owlzhangfq@gmail.com
 */
class ExpenseProjectOwnersJsonTest {
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new FinanceJsonConfiguration().financeMoneyModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test void historicalPrecheckRetainsExactBytesAndNewOwnerEvidenceSurvivesTheActualCodec() {
        UUID legal = UUID.randomUUID(); var date = LocalDate.of(2026, 10, 4); var now = Instant.parse("2026-10-04T10:00:00Z");
        var amount = new Money(new BigDecimal("10"), "CNY"); var zero = Money.zero("CNY");
        var line = new ExpenseLine(1, "OFFICE", date, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, amount, zero,
                List.of(), null, List.of(new CostAllocation("IT", "PROJECT-A", amount)), "合成项目费用", null);
        var content = new ExpenseContent(legal, ExpenseContent.Type.DAILY, "合成项目费用", List.of(line), List.of());
        var report = ExpenseReport.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "rate-v1", date),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic", "synthetic"), zero);
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(legal, "alice", "private-ref", "****1234", "a".repeat(64), "v1"),
                Map.of(1, assessment), "alice", now);
        var budget = new BudgetPrecheckPort.Assessment(BudgetPrecheckPort.Request.from(report, date), "budget-v1", now, now.plusSeconds(60));
        var entity = new FinanceCatalog.LegalEntity(legal, "合成法人", "CNY", false, "entity-v1", "Asia/Shanghai");
        var old = new ExpensePrecheckEvidence("catalog-v1", entity, date, budget, report.currentRound(), List.of(), List.of(), now.plusSeconds(60));
        var original = json.write(old);
        assertThat(original).doesNotContain("projectOwners");
        assertThat(json.write(json.read(original, ExpensePrecheckEvidence.class))).isEqualTo(original);
        var owners = new ExpenseProjectOwners("catalog-v1", legal, List.of(new FinanceCatalog.Project(legal, "PROJECT-A", "合成项目", "manager")));
        var current = new ExpensePrecheckEvidence(old.catalogVersion(), entity, date, budget, old.preview(), old.resources(), old.invoices(),
                old.validUntil(), old.policySelection(), old.priorControls(), owners);
        var serialized = json.write(current);
        assertThat(serialized).contains("\"projectOwners\"", "\"ownerSubject\":\"manager\"");
        assertThat(json.read(serialized, ExpensePrecheckEvidence.class)).isEqualTo(current);
        assertThat(json.write(json.read(serialized, ExpensePrecheckEvidence.class))).isEqualTo(serialized);
    }
}
