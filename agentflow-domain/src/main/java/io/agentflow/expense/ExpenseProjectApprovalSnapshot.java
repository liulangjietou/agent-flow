package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 原提交项目责任的不可变依据；审批执行状态仍属于既有轮次和任务。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseProjectApprovalSnapshot(int ruleVersion, ExpensePrecheckJob.Input precheck, long precheckVersion,
        long applicationVersion, long financialVersion, UUID definitionId, String processKey, long definitionVersion,
        String nodeId, Instant submittedAt, ExpenseProjectOwners owners) {
    public static final int RULE_VERSION = 1;
    private static final int READY_VERSION = 3;

    /** 只绑定原 READY 预检、紧接的财务冻结及申请修订；旧无项目定义允许没有项目节点。 */
    public ExpenseProjectApprovalSnapshot {
        if (ruleVersion != RULE_VERSION || precheck == null || precheckVersion != READY_VERSION
                || applicationVersion != precheck.applicationVersion() + 1 || financialVersion != precheck.financialVersion() + 1
                || definitionId == null || StringUtils.isBlank(processKey) || processKey.length() > 128 || definitionVersion < 1
                || submittedAt == null || owners == null || !owners.legalEntityId().equals(precheck.initiator().legalEntityId())
                || nodeId != null && (StringUtils.isBlank(nodeId) || nodeId.length() > 128)
                || !owners.projects().isEmpty() && nodeId == null) throw invalid();
    }

    /** 提交编排提供已确认预检和本次冻结修订，领域值一次核对完整来源。 */
    public static ExpenseProjectApprovalSnapshot capture(ExpenseReport report, long applicationVersion, UUID definitionId,
            String processKey, long definitionVersion, String nodeId, ExpensePrecheckJob checked) {
        if (checked.status() != ExpensePrecheckJob.Status.READY) throw invalid();
        var snapshot = new ExpenseProjectApprovalSnapshot(RULE_VERSION, checked.input(), checked.version(), applicationVersion,
                report.version(), definitionId, processKey, definitionVersion, nodeId, report.currentRound().submittedAt(), checked.result().evidence().projectOwners());
        if (!snapshot.matches(report, checked)) throw invalid();
        return snapshot;
    }

    /** 历史读取仅复核当时的有效事实，当前时间和后来目录版本不替换原责任。 */
    public boolean matches(ExpenseReport report, ExpensePrecheckJob checked) {
        if (checked.status() != ExpensePrecheckJob.Status.READY || checked.version() != precheckVersion || !precheck.equals(checked.input())
                || !tenantId().equals(report.tenantId()) || !reportId().equals(report.id()) || !applicationId().equals(report.applicationId())
                || !precheck.employeeId().equals(report.employeeId()) || report.version() != financialVersion || report.rounds().isEmpty()) return false;
        var evidence = checked.result().evidence(); var round = report.currentRound(); var preview = evidence.preview();
        return owners.equals(evidence.projectOwners()) && !submittedAt.isBefore(checked.completedAt()) && submittedAt.isBefore(evidence.validUntil())
                && round.roundNo() == roundNo() && round.submittedFinancialVersion() == precheck.financialVersion()
                && round.submittedBy().equals(precheck.employeeId()) && round.submittedAt().equals(submittedAt) && round.adjustments().isEmpty()
                && round.content().equals(preview.content()) && round.baseCurrency().equals(preview.baseCurrency()) && round.account().equals(preview.account())
                && round.originalLines().equals(preview.originalLines()) && round.approvedLines().equals(preview.approvedLines())
                && round.advanceOffsets().equals(preview.advanceOffsets());
    }

    public String tenantId() { return precheck.tenantId(); }
    public UUID reportId() { return precheck.reportId(); }
    public UUID applicationId() { return precheck.applicationId(); }
    public int roundNo() { return precheck.roundNo(); }
    public boolean hasProjects() { return !owners.projects().isEmpty(); }

    private static DomainException invalid() {
        return new DomainException("EXPENSE_PROJECT_SNAPSHOT_INVALID", "Project approval evidence must match the original ready precheck and unadjusted submission revision");
    }
}
