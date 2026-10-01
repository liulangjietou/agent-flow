package io.agentflow.approval.history;

import java.time.Instant;
import io.agentflow.approval.service.TaskAuditPort.MembershipChange;

/**
 * 历史查询的最小事实投影，不暴露申请正文或引擎变量。
 * @author owlzhangfq@gmail.com
 */
public record HistoryEvent(String id, long sequence, Instant occurredAt, Source source, String action,
                           Long aggregateVersion, Integer roundNo, String actor, String targetUser, String comment,
                           String nodeId, String nodeName, String nodeType, String taskId,
                           String processInstanceId, Long definitionVersion, String previousStatus, String currentStatus,
                           MembershipChange membershipChange) {
    /** 旧事件不补造会签变更事实。 */
    public HistoryEvent(String id, long sequence, Instant occurredAt, Source source, String action,
                        Long aggregateVersion, Integer roundNo, String actor, String targetUser, String comment,
                        String nodeId, String nodeName, String nodeType, String taskId,
                        String processInstanceId, Long definitionVersion, String previousStatus, String currentStatus) {
        this(id, sequence, occurredAt, source, action, aggregateVersion, roundNo, actor, targetUser, comment,
                nodeId, nodeName, nodeType, taskId, processInstanceId, definitionVersion, previousStatus, currentStatus, null);
    }
    /** 根据本次稳定排序赋予展示序号；序号不是持久事件顺序。 */
    public HistoryEvent sequenced(long value) {
        return new HistoryEvent(id, value, occurredAt, source, action, aggregateVersion, roundNo, actor,
                targetUser, comment, nodeId, nodeName, nodeType, taskId, processInstanceId, definitionVersion,
                previousStatus, currentStatus, membershipChange);
    }

    /**
     * 事件事实的原始来源。
     * @author owlzhangfq@gmail.com
     */
    public enum Source { APPLICATION_AUDIT, TASK_AUDIT, PROCESS_HISTORY, SUBMISSION_SNAPSHOT }
}
