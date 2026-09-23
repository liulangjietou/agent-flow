package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import java.util.Locale;

/**
 * 审批任务动作；回交只完成受托处理，不代表批准申请。
 * @author owlzhangfq@gmail.com
 */
public enum TaskAction {
    APPROVE, REJECT, RETURN, TRANSFER, DELEGATE, CLAIM, RELEASE, RESOLVE;

    /** 在动作入口将外部字符串转为固定动作集合。 */
    public static TaskAction parse(String value) {
        try { return valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new DomainException("INVALID_REQUEST", "Unsupported task action"); }
    }
}
