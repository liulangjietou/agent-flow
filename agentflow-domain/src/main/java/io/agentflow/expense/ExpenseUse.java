package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 财务资源预留的完整归属，旧轮次释放不能误删新轮次占用。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseUse(UUID reportId, int roundNo, int lineNo) {
    /** 行号零专用于整单借款冲销，发票和事前申请使用正行号。 */
    public ExpenseUse {
        if (reportId == null || roundNo < 1 || lineNo < 0 || lineNo > ExpenseContent.MAX_LINES) {
            throw new DomainException("INVALID_EXPENSE_USE", "A report, round and bounded line reference are required");
        }
    }
}
