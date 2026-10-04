package io.agentflow.approval.process;

import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.integration.ApprovalWebhookEvents;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务审计 JDBC 适配器，隔离审批应用服务与数据库表结构。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTaskAuditAdapter implements TaskAuditPort {
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;
    private final ApprovalWebhookEvents webhooks;

    /** 创建审计适配器。 */
    public JdbcTaskAuditAdapter(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil, ApprovalWebhookEvents webhooks) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
        this.webhooks = webhooks;
    }

    @Override
    public String record(TaskOperation operation) {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
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
        if (operation.membershipChange() != null) payload.put("membershipChange", operation.membershipChange());
        if (operation.proxyUse() != null) payload.put("proxyUse", operation.proxyUse());
        if (operation.duplicateApproval() != null) payload.put("duplicateApproval", operation.duplicateApproval());
        jdbcTemplate.update("""
                INSERT INTO audit_event
                (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, application_id, action, actor_id, payload_json, occurred_at)
                VALUES (?, ?, ?, 'Task', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), operation.tenantId(), eventId, operation.taskId(),
                operation.aggregateVersion(), operation.applicationId().toString(), operation.action(), operation.actor(), jsonUtil.write(payload), Timestamp.from(occurredAt));
        webhooks.task(operation, eventId, occurredAt);
        return eventId;
    }
}
