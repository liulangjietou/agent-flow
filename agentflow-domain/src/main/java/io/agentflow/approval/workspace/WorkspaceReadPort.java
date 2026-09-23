package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 个人工作台的只读投影端口，不加载或改变申请正文与聚合状态。
 * @author owlzhangfq@gmail.com
 */
public interface WorkspaceReadPort {
    /** 按认证主体读取自己创建的申请摘要，最多返回 limit + 1 条供分页判断。 */
    List<ApplicationItem> applications(Actor actor, Query query);

    /** 按真实操作人读取已办理记录，不以当前任务指派人推断处理人。 */
    List<HandledItem> handled(Actor actor, Query query);

    /**
     * 已在接口入口校验的固定筛选与游标位置。
     * @author owlzhangfq@gmail.com
     */
    record Query(boolean drafts, String text, String status, String action, int limit,
                 Instant beforeTime, UUID beforeId) { }

    /**
     * 申请摘要仅包含列表需要的信息；正文通过原详情授权接口读取。
     * @author owlzhangfq@gmail.com
     */
    record ApplicationItem(UUID id, String businessNo, String title, String processKey, long definitionVersion,
                           String status, int roundNo, Instant createdAt, Instant updatedAt) { }

    /**
     * 一次实际办理事实，动作结果与申请当前状态分别展示。
     * @author owlzhangfq@gmail.com
     */
    record HandledItem(UUID id, String taskId, UUID applicationId, String businessNo, String title,
                       String processKey, long definitionVersion, String applicationStatus, String action,
                       Instant handledAt, Integer roundNo, String nodeName, String comment,
                       String targetUser, String handledStatus) { }
}
