package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.UUID;

/**
 * 审批申请与结构化业务的不可变绑定，不能从用户表单中解释或改写。
 * @author owlzhangfq@gmail.com
 */
public record BusinessReference(Type type, UUID id) {
    /** 业务类型和业务标识必须同时存在。 */
    public BusinessReference {
        if (type == null || id == null) throw new DomainException("INVALID_BUSINESS_REFERENCE", "Business type and identifier are required");
    }

    /**
     * 已经接入结构化写入入口的业务种类。
     * @author owlzhangfq@gmail.com
     */
    public enum Type { EXPENSE, EXPENSE_PLAN, ADVANCE_REQUEST, PROCUREMENT_PAYMENT, BUDGET_ADJUSTMENT }
}
