package io.agentflow.expense.reporting;

import io.agentflow.common.CurrentActor;
import io.agentflow.expense.EmployeeAdvanceRepository;
import io.agentflow.expense.ExpenseRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 当前余额沿平台原批准业务读取，外部导入且没有原轮次读取依据的资源不获得新访问权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseReportingResources {
    private final JdbcTemplate jdbc;
    private final CurrentActor actors;
    private final ExpenseReportScope scope;
    private final EmployeeAdvanceRepository advances;
    private final ExpenseRequestRepository requests;

    /** 余额使用领域聚合的真实消费与还款计算，查询不访问外部财务系统。 */
    public ExpenseReportingResources(JdbcTemplate jdbc, CurrentActor actors, ExpenseReportScope scope,
                                     EmployeeAdvanceRepository advances, ExpenseRequestRepository requests) {
        this.jdbc = jdbc; this.actors = actors; this.scope = scope; this.advances = advances; this.requests = requests;
    }

    /** 所选日期仅约束轮次指标；这里明确返回生成时的已授权存量。 */
    public ExpenseFinancialReport.Resources read(ExpenseReportQueryParameters.Query query, Instant now) {
        var advanceValues = new ArrayList<ExpenseResourceMetrics.Advance>();
        if (query.categoryCode() == null) {
            for (var candidate : candidates("ADVANCE")) {
                var allowed = scope.advance(candidate.id(), candidate.round()).filter(value -> ExpenseReportScope.organization(query, value.original()));
                if (allowed.isEmpty()) continue;
                var source = allowed.get(); var view = source.view();
                var balance = advances.find(actors.actor().tenantId(), view.id()).orElseThrow(ExpenseReportScope::unavailable);
                if (view.approval() == null || view.approval().roundNo() != candidate.round()
                        || !balance.employeeId().equals(source.original().submittedBy()) || !balance.legalEntityId().equals(view.content().legalEntityId())
                        || !balance.balance().limit().equals(view.content().amount())) throw ExpenseReportScope.unavailable();
                advanceValues.add(new ExpenseResourceMetrics.Advance(balance.outstanding(), balance.dueOn(),
                        balance.paymentReviewRequired() || balance.voucherReviewRequired() || balance.repaymentReviewRequired(),
                        LocalDate.ofInstant(now, ZoneId.of(view.financialRound().legalEntity().timeZone()))));
            }
        }
        var priorValues = new ArrayList<ExpenseResourceMetrics.Prior>();
        for (var candidate : candidates("PRIOR_REQUEST")) {
            var allowed = scope.plan(candidate.id(), candidate.round()).filter(value -> ExpenseReportScope.organization(query, value.original()));
            if (allowed.isEmpty()) continue;
            var source = allowed.get(); var view = source.view();
            var credit = requests.find(actors.actor().tenantId(), view.id()).orElseThrow(ExpenseReportScope::unavailable);
            if (!credit.applicationId().equals(view.applicationId()) || !credit.employeeId().equals(source.original().submittedBy())
                    || !credit.legalEntityId().equals(view.content().legalEntityId())) throw ExpenseReportScope.unavailable();
            for (var original : view.financialRound().lines()) {
                if (query.categoryCode() != null && !query.categoryCode().equals(original.original().categoryCode())) continue;
                var approved = credit.approvedLines().stream().filter(line -> line.lineNo() == original.original().lineNo()).findFirst().orElseThrow(ExpenseReportScope::unavailable);
                if (!approved.approvedAmount().equals(original.amount())
                        || !approved.policyReference().equals("APPROVAL:"+view.applicationId()+":"+candidate.round())) throw ExpenseReportScope.unavailable();
                var balance = credit.balance(approved.lineNo());
                priorValues.add(new ExpenseResourceMetrics.Prior(approved.approvedAmount(), balance.consumed(), balance.reserved()));
            }
        }
        return new ExpenseFinancialReport.Resources(now, query.categoryCode() == null,
                ExpenseResourceMetrics.advances(advanceValues), ExpenseResourceMetrics.priorRequests(priorValues));
    }
    private List<Candidate> candidates(String kind) {
        // 表名仅来自内部两种既有来源，筛选值和租户始终使用绑定参数。
        String table = kind.equals("ADVANCE") ? "advance_request" : "expense_plan";
        return jdbc.query("SELECT p.id,s.round_no FROM "+table+" p JOIN finance_resource f ON f.tenant_id=p.tenant_id AND f.id=p.id AND f.resource_type=? "
                + "JOIN approval_submission_round s ON s.tenant_id=p.tenant_id AND s.application_id=p.application_id AND s.status='APPROVED' "
                + "WHERE p.tenant_id=? ORDER BY p.id,s.round_no", (row, index) -> new Candidate(UUID.fromString(row.getString("id")), row.getInt("round_no")),
                kind, actors.actor().tenantId());
    }
    /**
     * 只扫描来源标识，敏感余额在逐轮授权之后读取。
     * @author owlzhangfq@gmail.com
     */
    private record Candidate(UUID id, int round) { }
}
