package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一份草稿或一个获授权轮次的财务读模型，不一次性暴露全部历史轮次。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseResponse(UUID id, UUID applicationId, String businessNo, ApplicationStatus applicationStatus,
                               long applicationVersion, long financialVersion, int roundNo, boolean editable,
                               ExpenseContent content, FinancialRound financialRound, BudgetRetention budgetRetention) {
    /**
     * 仅在完整费用明细授权后返回所选轮次的期限和处理结果，不返回预算账户与外部凭据。
     * @author owlzhangfq@gmail.com
     */
    public record BudgetRetention(int roundNo, SubmissionRound.Status stoppedStatus, Instant retainedAt, int retentionDays,
                                  Instant expiresAt, ExpenseBudgetRetention.Status status, UUID releaseOperationId,
                                  String issue, Instant updatedAt) {
        /** 历史轮次沿用原期限，不能根据当前租户配置重算。 */
        public static BudgetRetention from(ExpenseBudgetRetention value) {
            return new BudgetRetention(value.roundNo(), value.stoppedStatus(), value.retainedAt(), value.policy().retentionDays(),
                    value.expiresAt(), value.status(), value.releaseOperationId(), value.issue(), value.updatedAt());
        }
    }
    /**
     * 账户仅显示外部已脱敏文本，内部账户引用和付款绑定摘要不会返回到页面。
     * @author owlzhangfq@gmail.com
     */
    public record FinancialRound(int roundNo, long submittedFinancialVersion, String submittedBy, Instant submittedAt,
                                  String baseCurrency, String maskedAccount, List<ExpenseRound.FrozenLine> originalLines,
                                  List<ExpenseRound.ApprovedLine> approvedLines, List<AdvanceOffset> advanceOffsets,
                                  List<ExpenseAdjustment> adjustments, Money approvedGross, Money approvedTax,
                                  Money offsetTotal, Money payable) {
        /** 由已经授权的单轮财务事实构建，不重新计算税率或获取账户。 */
        public static FinancialRound from(ExpenseRound round) {
            return new FinancialRound(round.roundNo(), round.submittedFinancialVersion(), round.submittedBy(), round.submittedAt(),
                    round.baseCurrency(), round.account().maskedAccount(), round.originalLines(), round.approvedLines(),
                    round.advanceOffsets(), round.adjustments(), round.approvedGross(), round.approvedTax(), round.offsetTotal(), round.payable());
        }
    }
}
