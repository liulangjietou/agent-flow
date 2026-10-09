package io.agentflow.expense;

import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 人工确认补正后的不可变回执，把原模型建议、已保存版本和新预检关联起来。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseCorrection(UUID runId, UUID reportId, UUID applicationId, long applicationVersion,
                                long financialVersion, UUID precheckId, String appliedBy, Instant appliedAt) {
    /** 回执只表达保存及排队事实，不表达预检通过或审批通过。 */
    public ExpenseCorrection {
        if (runId == null || reportId == null || applicationId == null || applicationVersion < 2
                || financialVersion < 2 || precheckId == null || StringUtils.isBlank(appliedBy) || appliedAt == null) {
            throw new IllegalArgumentException("Invalid expense correction receipt");
        }
    }
}
