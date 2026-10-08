package io.agentflow.approval.process;

import io.agentflow.approval.process.mapper.TaskAuditAdapterMapper;
import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.integration.ApprovalWebhookEvents;
import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务审计 JDBC 适配器，隔离审批应用服务与数据库表结构。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTaskAuditAdapter implements TaskAuditPort {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcTaskAuditAdapter.class);
    private final TaskAuditAdapterMapper sqlMapper;
    private final JsonUtil jsonUtil;
    private final ApprovalWebhookEvents webhooks;

    /** 创建审计适配器。 */
    public JdbcTaskAuditAdapter(
            TaskAuditAdapterMapper sqlMapper, JsonUtil jsonUtil, ApprovalWebhookEvents webhooks) {
        this.sqlMapper = sqlMapper;
        this.jsonUtil = jsonUtil;
        this.webhooks = webhooks;
    }

    @Override
    public String record(TaskOperation operation) {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        try (var scope =
                new DiagnosticContext(
                                DiagnosticContext.currentIdOr(eventId),
                                operation.tenantId(),
                                operation.businessNo(),
                                operation.processInstanceId(),
                                operation.taskId())
                        .open()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", DiagnosticContext.currentIdOr(eventId));
            payload.put("businessNo", operation.businessNo());
            payload.put("taskId", operation.taskId());
            payload.put("action", operation.action());
            payload.put("actor", operation.actor());
            payload.put("comment", operation.comment() == null ? "" : operation.comment());
            payload.put("applicationId", operation.applicationId().toString());
            payload.put("roundNo", operation.roundNo());
            payload.put("processInstanceId", operation.processInstanceId());
            payload.put("targetUser", operation.targetUser());
            payload.put("nodeId", operation.nodeId());
            payload.put("nodeName", operation.nodeName());
            payload.put("previousStatus", operation.previousStatus());
            payload.put("currentStatus", operation.currentStatus());
            if (operation.membershipChange() != null)
                payload.put("membershipChange", operation.membershipChange());
            if (operation.proxyUse() != null) payload.put("proxyUse", operation.proxyUse());
            if (operation.duplicateApproval() != null)
                payload.put("duplicateApproval", operation.duplicateApproval());
            if (operation.budgetConfirmation() != null)
                payload.put("budgetConfirmation", operation.budgetConfirmation());
            sqlMapper.record(
                    UUID.randomUUID().toString(),
                    operation.tenantId(),
                    eventId,
                    operation.taskId(),
                    operation.aggregateVersion(),
                    operation.applicationId().toString(),
                    operation.action(),
                    operation.actor(),
                    jsonUtil.write(payload),
                    Timestamp.from(occurredAt));
            LOG.info("Approval audit staged, errorCode={}, eventId={}", "NONE", eventId);
            webhooks.task(operation, eventId, occurredAt);
            return eventId;
        }
    }
}
