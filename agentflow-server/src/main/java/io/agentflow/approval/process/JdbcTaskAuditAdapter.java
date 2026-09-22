package io.agentflow.approval.process;

import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.JsonUtil;
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

    /** 创建审计适配器。 */
    public JdbcTaskAuditAdapter(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public String record(TaskOperation operation) {
        String eventId = UUID.randomUUID().toString();
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
        jdbcTemplate.update("""
                INSERT INTO audit_event
                (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, application_id, action, payload_json, occurred_at)
                VALUES (?, ?, ?, 'Task', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), operation.tenantId(), eventId, operation.taskId(),
                operation.aggregateVersion(), operation.applicationId().toString(), operation.action(), jsonUtil.write(payload));
        return eventId;
    }
}
