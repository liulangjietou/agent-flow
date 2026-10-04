package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 费用填报的跨聚合入口，协调申请权限与版本；金额和内容自身约束归报销聚合。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseDraftService {
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;
    private final ExpenseAllowancePreparation allowances;
    private final JdbcExpenseBudgetRetentionRepository retentions;

    /** 注入已有申请授权及字段投影，不根据 ADMIN 角色放宽财务读取。 */
    public ExpenseDraftService(ExpenseReportRepository reports, ApprovalApplicationFacade applications,
                                ApplicationFieldViews fields, CurrentActor actors, ExpenseAllowancePreparation allowances,
                                JdbcExpenseBudgetRetentionRepository retentions) {
        this.reports = reports; this.applications = applications; this.fields = fields; this.actors = actors; this.allowances = allowances;
        this.retentions = retentions;
    }

    /** 创建一对一业务绑定、空或完整费用草稿和财务版本证据。 */
    @Transactional
    public ExpenseResponse create(String businessNo, String processKey, long definitionVersion, ExpenseAllowancePreparation.Prepared prepared) {
        var content = allowances.requireCurrent(prepared);
        var actor = actors.actor(); UUID reportId = UUID.randomUUID();
        var application = applications.createBusiness(businessNo, processKey, definitionVersion, content.title(),
                ExpenseFormContract.draftPayload(), new BusinessReference(BusinessReference.Type.EXPENSE, reportId));
        var report = ExpenseReport.draft(reportId, actor.tenantId(), application.id(), actor.userId(), content);
        reports.create(report, actor.userId());
        return response(application, report, null, true);
    }

    /** 同时校验申请与财务版本，任一冲突使两个聚合和各自审计一起回滚。 */
    @Transactional
    public ExpenseResponse revise(UUID reportId, long applicationVersion, long financialVersion, ExpenseAllowancePreparation.Prepared prepared) {
        var report = require(reportId);
        var application = applications.requireApplicant(report.applicationId());
        application.requireEditable(applicationVersion);
        var content = allowances.requireCurrent(prepared);
        report.revise(financialVersion, content);
        application = applications.reviseBusiness(application.id(), applicationVersion, content.title(),
                ExpenseFormContract.draftPayload(), application.businessReference());
        reports.update(report, financialVersion, actors.actor().userId(), "REVISE");
        return response(application, report, null, true);
    }

    /** 每次读取复核该轮节点权限；旧审批人不会因参与过往轮次获得当前补正内容。 */
    // 领域读取拒绝没有写入，上层只读聚合可排除不可读来源而不污染其余读取事务。
    @Transactional(readOnly = true, noRollbackFor = DomainException.class)
    public ExpenseResponse read(UUID reportId, Integer roundNo) {
        var report = require(reportId);
        var application = applications.get(report.applicationId());
        boolean applicant = actors.actor().userId().equals(report.employeeId());
        if (roundNo == null && application.editable()) {
            if (!applicant) throw notFound();
            return response(application, report, null, true);
        }
        int selected = roundNo == null ? application.roundNo() : roundNo;
        if (selected < 1) throw new DomainException("INVALID_EXPENSE_QUERY", "Round number must be positive");
        var round = report.rounds().stream().filter(item -> item.roundNo() == selected).findFirst().orElseThrow(ExpenseDraftService::notFound);
        var projection = fields.attachmentView(application, selected);
        if (!applicant && !ExpenseFormContract.detailsReadable(application.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading expense details");
        }
        return response(application, report, round, false);
    }

    private ExpenseReport require(UUID id) {
        return reports.find(actors.actor().tenantId(), id).orElseThrow(ExpenseDraftService::notFound);
    }

    private ExpenseResponse response(Application application, ExpenseReport report, ExpenseRound round, boolean editable) {
        int selected = round == null ? application.roundNo() : round.roundNo();
        var retention = retentions.find(report.tenantId(), report.id(), selected).map(ExpenseResponse.BudgetRetention::from).orElse(null);
        return new ExpenseResponse(report.id(), application.id(), application.businessNo(), application.status(), application.version(),
                report.version(), selected, editable, round == null ? report.content() : round.content(),
                round == null ? null : ExpenseResponse.FinancialRound.from(round), retention);
    }

    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense report or round not found"); }
}
