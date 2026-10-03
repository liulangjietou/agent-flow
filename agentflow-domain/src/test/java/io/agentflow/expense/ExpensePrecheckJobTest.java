package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetPrecheckPort;
import io.agentflow.finance.EmployeeAccountSnapshot;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.agentflow.expense.ExpensePrecheckJob.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 预检租约、终态、版本绑定和法人业务日边界。
 * @author owlzhangfq@gmail.com
 */
class ExpensePrecheckJobTest {
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-28");
    private static final UUID ENTITY = UUID.randomUUID();

    @Test
    void timeoutBoundaryCannotPublishLateReadyOrOverwriteTerminalResult() {
        var input = input(); var job = ExpensePrecheckJob.queue(input, NOW);
        var running = job.start(NOW, NOW.plusSeconds(30));
        var result = new Result(evidence(input, NOW.plusSeconds(300)), List.of());
        assertThat(running.finish(result, NOW.plusSeconds(29)).status()).isEqualTo(Status.READY);
        var expired = running.finish(result, NOW.plusSeconds(30));
        assertThat(expired.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(expired.result().findings()).containsExactly(new Finding(Stage.SYSTEM, null, Nature.UNAVAILABLE, "TIMEOUT"));
        assertThat(expired.result().evidence()).isNull();
        assertThatThrownBy(() -> expired.finish(result, NOW.plusSeconds(31))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.start(NOW, NOW.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThat(job.status()).isEqualTo(Status.QUEUED);
    }

    @Test
    void failedOrExpiredEvidenceAndAnotherReportCannotBecomeReady() {
        var input = input(); var running = ExpensePrecheckJob.queue(input, NOW).start(NOW, NOW.plusSeconds(30));
        assertThatThrownBy(() -> running.finish(new Result(evidence(input(), NOW.plusSeconds(300)), List.of()), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> running.finish(new Result(evidence(input, NOW.plusSeconds(5)), List.of()), NOW.plusSeconds(5))).isInstanceOf(DomainException.class);
        var blocked = new Result(null, List.of(new Finding(Stage.BUDGET, null, Nature.REJECTED, "BUDGET_INSUFFICIENT")));
        assertThat(running.finish(blocked, NOW).status()).isEqualTo(Status.BLOCKED);
        assertThatThrownBy(() -> new Result(evidence(input, NOW.plusSeconds(10)), blocked.findings())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Result(null, List.of())).isInstanceOf(DomainException.class);
    }

    @Test
    void legalEntityRequiresAnExplicitValidTimezone() {
        assertThat(new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "Pacific/Kiritimati").timeZone()).isEqualTo("Pacific/Kiritimati");
        for (String invalid : List.of("", "system-default", "Asia/Unknown")) {
            assertThatThrownBy(() -> new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", invalid)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", null)).isInstanceOf(DomainException.class);
    }

    private Input input() {
        return new Input(UUID.randomUUID(), "tenant", UUID.randomUUID(), UUID.randomUUID(), "alice", 1, 1, 1, 1,
                new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, ENTITY, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位"), DATE, "a".repeat(64));
    }
    private ExpensePrecheckEvidence evidence(Input input, Instant until) {
        var amount = new Money(new BigDecimal("10"), "CNY");
        var line = new ExpenseLine(1, "OFFICE", DATE, null, "SH", BigDecimal.ONE, ExpenseLine.Unit.ITEM, amount, Money.zero("CNY"), List.of(), null,
                List.of(new CostAllocation("IT", null, amount)), "合成费用", null);
        var report = ExpenseReport.draft(input.reportId(), input.tenantId(), input.applicationId(), input.employeeId(), new ExpenseContent(ENTITY, ExpenseContent.Type.DAILY, "合成预检", List.of(line), List.of()));
        var assessment = new ExpenseAssessment(new ExpenseExchangeRate("CNY", "CNY", BigDecimal.ONE, "synthetic", DATE),
                new ExpensePolicySnapshot(UUID.randomUUID(), 1, amount, amount, ExpensePolicySnapshot.Decision.WITHIN_LIMIT, "synthetic", "synthetic"), Money.zero("CNY"));
        report.freeze(1, 1, "CNY", new EmployeeAccountSnapshot(ENTITY, "alice", "private-ref", "****1234", "a".repeat(64), "v1"), Map.of(1, assessment), "alice", NOW);
        var budget = new BudgetPrecheckPort.Assessment(BudgetPrecheckPort.Request.from(report, DATE), "synthetic-budget", NOW.minusSeconds(1), until);
        return new ExpensePrecheckEvidence("v1", new FinanceCatalog.LegalEntity(ENTITY, "法人", "CNY", false, "v1", "Asia/Shanghai"), DATE, budget, report.currentRound(), List.of(), List.of(), until);
    }
}
