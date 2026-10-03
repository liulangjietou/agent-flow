package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.util.UUID;

/**
 * 借款冲销选择；零金额只用于保存已核减至零的审计事实。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceOffset(UUID advanceId, Money amount) {
    /** 借款所有人、法人和可用余额由借款聚合及应用服务校验。 */
    public AdvanceOffset {
        if (advanceId == null || amount == null) throw new DomainException("INVALID_ADVANCE_OFFSET", "An advance and offset amount are required");
    }
}
