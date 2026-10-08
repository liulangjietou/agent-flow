package io.agentflow.expense.reporting;


import io.agentflow.common.CurrentActor;
import io.agentflow.expense.JdbcExpensePrecheckRepository;
import io.agentflow.expense.JdbcInvoiceVerificationRepository;
import io.agentflow.expense.reporting.mapper.ExpenseReportingActivityMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 尝试指标只来自已授权轮次引用的原件和原目标轮次，不能观察后来私人草稿的操作。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReportingActivity {
    private static final String OCCUPIED = "INVOICE_OCCUPIED";
    private final ExpenseReportingActivityMapper sqlMapper;
    private final CurrentActor actors;
    private final JdbcExpensePrecheckRepository prechecks;
    private final JdbcInvoiceVerificationRepository verifications;

    /** 读取持久化结果及覆盖起点，不在报告中重新验票或推断旧拒绝。 */
    public ExpenseReportingActivity(
            ExpenseReportingActivityMapper sqlMapper,
            CurrentActor actors,
            JdbcExpensePrecheckRepository prechecks,
            JdbcInvoiceVerificationRepository verifications) {
        this.sqlMapper = sqlMapper;
        this.actors = actors;
        this.prechecks = prechecks;
        this.verifications = verifications;
    }

    /** 一个原件在可读轮次中去重，后来的私人查验及其完成结果不计入。 */
    public ExpenseFinancialReport.Activity read(
            List<ExpenseReportScope.Expense> sources, ExpenseReportQueryParameters.Query query) {
        String tenant = actors.actor().tenantId();
        Map<UUID, Instant> cutoffs = new HashMap<>();
        long duplicates = 0, submissions = 0;
        for (var source : sources) {
            for (var line : source.view().financialRound().originalLines()) {
                if (query.categoryCode() != null
                        && !query.categoryCode().equals(line.original().categoryCode())) continue;
                for (UUID invoice : line.original().invoiceIds())
                    cutoffs.merge(
                            invoice,
                            source.original().submittedAt(),
                            (first, second) -> first.isAfter(second) ? first : second);
            }
            var ids =
                    sqlMapper.read(
                            tenant,
                            source.view().id().toString(),
                            Timestamp.from(query.start()),
                            Timestamp.from(query.end()));
            for (String id : ids) {
                var job =
                        prechecks
                                .find(tenant, UUID.fromString(id))
                                .orElseThrow(ExpenseReportScope::unavailable);
                if (job.input().roundNo() == source.view().roundNo()
                        && job.result() != null
                        && job.result().findings().stream()
                                .anyMatch(finding -> OCCUPIED.equals(finding.code()))) duplicates++;
            }
            submissions +=
                    SqlRows.single(
                            sqlMapper.read2(
                                    tenant,
                                    source.view().id().toString(),
                                    source.view().roundNo(),
                                    Timestamp.from(query.start()),
                                    Timestamp.from(query.end())));
        }
        long succeeded = 0, rejected = 0, unavailable = 0, pending = 0;
        for (var invoice : cutoffs.entrySet()) {
            var ids =
                    sqlMapper.read3(
                            tenant,
                            invoice.getKey().toString(),
                            Timestamp.from(query.start()),
                            Timestamp.from(query.end()),
                            Timestamp.from(invoice.getValue()));
            for (String id : ids) {
                var job =
                        verifications
                                .find(tenant, UUID.fromString(id))
                                .orElseThrow(ExpenseReportScope::unavailable);
                if (job.completedAt() == null || job.completedAt().isAfter(invoice.getValue())) {
                    pending++;
                    continue;
                }
                switch (job.status()) {
                    case SUCCEEDED -> succeeded++;
                    case REJECTED -> rejected++;
                    case UNAVAILABLE -> unavailable++;
                    case QUEUED, RUNNING -> pending++;
                }
            }
        }
        Instant started =
                SqlRows.single(
                        SqlRows.map(
                                sqlMapper.read4(),
                                row -> row.getTimestamp("started_at").toInstant()));
        return new ExpenseFinancialReport.Activity(
                new ExpenseFinancialReport.Verification(
                        succeeded,
                        rejected,
                        unavailable,
                        pending,
                        ExpenseFinancialMetrics.ratio(
                                BigDecimal.valueOf(rejected),
                                BigDecimal.valueOf(succeeded + rejected))),
                duplicates,
                new ExpenseFinancialReport.DuplicateSubmissions(
                        submissions, started, query.start().isBefore(started)));
    }
}
