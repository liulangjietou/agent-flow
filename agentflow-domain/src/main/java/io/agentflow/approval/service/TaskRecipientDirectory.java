package io.agentflow.approval.service;

import java.util.List;

/**
 * 当前租户中可接收审批任务的有效账号来源，供展示与执行时复核共同使用。
 * @author owlzhangfq@gmail.com
 */
public interface TaskRecipientDirectory {
    /** 返回当前身份源中具有审批资格的账号，不通过客户端输入推定资格。 */
    List<String> approvers(String tenantId);
}
