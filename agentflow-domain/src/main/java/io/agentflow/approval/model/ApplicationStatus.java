package io.agentflow.approval.model;

/**
 * 审批申请生命周期；支付状态由财务结算上下文单独管理。
 * @author owlzhangfq@gmail.com
 */
public enum ApplicationStatus {
    DRAFT, IN_APPROVAL, RETURNED, WITHDRAWN, REJECTED, APPROVED, REVOKED, CANCELLED
}
