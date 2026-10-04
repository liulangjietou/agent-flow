package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 正式提交的不可变额度依据，后续核减和其他报销不倒改本轮追加审批决定。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePriorControlSnapshot(String tenantId, UUID reportId, UUID applicationId, long applicationVersion,
        int roundNo, long financialVersion, UUID definitionId, long definitionVersion, Instant submittedAt,
        List<ExpensePriorControlAssessment> assessments) {
    /** 同一轮次每条费用只能有一份来源依据，空集合表示本轮没有使用事前额度。 */
    public ExpensePriorControlSnapshot {
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || reportId == null || applicationId == null || applicationVersion < 1
                || roundNo < 1 || financialVersion < 2 || definitionId == null || definitionVersion < 1 || submittedAt == null || assessments == null
                || assessments.size() > ExpenseContent.MAX_LINES || assessments.stream().anyMatch(java.util.Objects::isNull)
                || assessments.stream().map(ExpensePriorControlAssessment::lineNo).distinct().count() != assessments.size()) {
            throw new DomainException("INVALID_EXPENSE_PRIOR_SNAPSHOT", "Prior control snapshot requires the original submission identity and unique line assessments");
        }
        assessments = List.copyOf(assessments);
    }

    /** 总标志只能由本轮已经冻结的各行实际超容差依据派生。 */
    public boolean requiresApproval() { return assessments.stream().anyMatch(ExpensePriorControlAssessment::requiresApproval); }
}
