package io.agentflow.approval.service;

import java.util.List;
import java.util.Set;

/**
 * 当前租户中可接收审批任务的有效账号来源，供展示与执行时复核共同使用。
 * @author owlzhangfq@gmail.com
 */
public interface TaskRecipientDirectory {
    /** 本地目录可收紧办理资格，身份源系统角色仍由审批入口独立要求。 */
    default boolean eligible(String tenantId, String userId) { return true; }

    /** 返回当前身份源中具有审批资格的账号，不通过客户端输入推定资格。 */
    List<String> approvers(String tenantId);

    /** 将实际任务中的指定账号与候选角色映射为有效审批账号。 */
    List<String> members(String tenantId, Set<String> users, Set<String> roles);
}
