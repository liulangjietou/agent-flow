package io.agentflow.approval;

import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.integration.ApprovalWebhookEvents;
import io.agentflow.observability.DiagnosticContext;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 申请审计使用共享事件存储，并保留独立的申请聚合身份。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationAuditAdapter implements ApplicationAuditPort {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ApprovalWebhookEvents webhooks;

    /** 使用申请事务共用的数据源记录审计。 */
    public JdbcApplicationAuditAdapter(JdbcTemplate jdbc, JsonUtil json, ApprovalWebhookEvents webhooks) {
        this.jdbc = jdbc;
        this.json = json;
        this.webhooks = webhooks;
    }

    @Override
    public void record(ApplicationOperation operation) {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", DiagnosticContext.currentIdOr(eventId));
        payload.put("action", operation.action().name());
        payload.put("actor", operation.actor());
        payload.put("applicationId", operation.applicationId().toString());
        payload.put("roundNo", operation.roundNo());
        payload.put("processInstanceId", operation.processInstanceId());
        payload.put("previousStatus", operation.previousStatus());
        payload.put("currentStatus", operation.currentStatus());
        payload.put("comment", operation.comment());
        jdbc.update("""
                INSERT INTO audit_event
                (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, application_id, action, actor_id, payload_json, occurred_at)
                VALUES (?, ?, ?, 'Application', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), operation.tenantId(), eventId,
                operation.applicationId().toString(), operation.aggregateVersion(), operation.applicationId().toString(),
                operation.action().name(), operation.actor(), json.write(payload), Timestamp.from(occurredAt));
        webhooks.application(operation, eventId, occurredAt);
    }
}
