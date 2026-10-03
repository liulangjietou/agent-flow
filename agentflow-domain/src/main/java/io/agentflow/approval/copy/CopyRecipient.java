package io.agentflow.approval.copy;

import java.time.Instant;
import java.util.UUID;

/**
 * 节点实际到达时冻结的抄送接收事实，仅授予本轮快照读取权，不构成审批参与身份。
 * @author owlzhangfq@gmail.com
 */
public record CopyRecipient(String tenantId, UUID applicationId, int roundNo, String processInstanceId,
                            String nodeId, String nodeName, String recipient, String rule, long directoryRevision,
                            Instant createdAt) {
    public static final int MAX_RECIPIENTS = 100;
}
