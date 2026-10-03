package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.flowable.engine.HistoryService;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * 申请人撤回与作废的财务入口，申请状态、财务版本与预留释放在同一锁内处理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseLifecycleService {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ExpenseReleaseService releases;
    private final JdbcExpenseSubmissionControlRepository controls;
    private final SubmissionRoundRepository rounds;
    private final HistoryService history;

    /** 复用申请权限，管理员读取权限不能转化为申请人写权限。 */
    public ExpenseLifecycleService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications, ExpenseReleaseService releases,
            JdbcExpenseSubmissionControlRepository controls, SubmissionRoundRepository rounds, HistoryService history) {
        this.actors = actors; this.reports = reports; this.applications = applications; this.releases = releases;
        this.controls = controls; this.rounds = rounds; this.history = history;
    }

    /** 撤回终止审批，保留实际预留；作废释放预留并保留所有历史轮次。 */
    @Transactional
    public Receipt change(UUID reportId, Input input, boolean cancel) {
        var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var report = reports.find(actor.tenantId(), reportId).orElseThrow();
        var application = applications.requireApplicant(report.applicationId());
        if (report.version() != input.financialVersion()) throw new DomainException("CONCURRENCY_CONFLICT", "Financial version changed");
        if (!cancel && application.status() == ApplicationStatus.IN_APPROVAL && !withdrawalAllowed(application)) {
            throw new DomainException("EXPENSE_WITHDRAWAL_NOT_ALLOWED", "Expense withdrawal is not allowed after financial review has started");
        }
        application = cancel
                ? applications.cancelBusiness(application.id(), input.applicationVersion(), input.comment(), application.businessReference())
                : applications.withdrawBusiness(application.id(), input.applicationVersion(), input.comment(), application.businessReference());
        if (cancel) releases.release(application, actor.userId(), Instant.now());
        return new Receipt(reportId, application.id(), application.version(), report.version(), application.status().name());
    }

    /** 写入与已授权详情共用本轮撤回规则；调用方另行核对本人身份。 */
    public boolean withdrawalAllowed(Application application) {
        if (application.status() != ApplicationStatus.IN_APPROVAL) return false;
        var control = controls.find(application.tenantId(), application.businessReference().id(), application.roundNo()).orElseThrow(
                () -> new DomainException("EXPENSE_TASK_CONTEXT_CHANGED", "Expense submission control not found"));
        var round = rounds.findByRound(application.tenantId(), application.id(), application.roundNo()).orElseThrow(
                () -> new DomainException("EXPENSE_TASK_CONTEXT_CHANGED", "Expense approval round not found"));
        // 只看当前待办会在财务已完成、流转到后续节点时重新放开撤回，因此核对本轮实际进入历史。
        boolean entered = history.createHistoricTaskInstanceQuery().processInstanceId(round.processInstanceId()).list().stream()
                .anyMatch(task -> control.stage(task.getTaskDefinitionKey()).finance());
        return !entered;
    }

    /**
     * 申请人只能指定当前双版本和撤回或作废原因。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull @Positive Long applicationVersion, @NotNull @Positive Long financialVersion,
            @NotBlank @Size(max = 2000) String comment) {
        /** 财务写入拒绝未知字段。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown expense lifecycle request field"); }
    }
    /**
     * 不把敏感明细保存在幂等返回正文。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID reportId, UUID applicationId, long applicationVersion, long financialVersion, String status) { }
}
