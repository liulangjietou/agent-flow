package io.agentflow.approval.process;

import io.agentflow.approval.service.TaskAuditPort;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
    public String record(String tenantId, String taskId, long aggregateVersion, String actor,
                         String action, String comment) {
        String eventId = UUID.randomUUID().toString();
        String payload = jsonUtil.write(Map.of("action", action, "actor", actor,
                "comment", comment == null ? "" : comment));
        jdbcTemplate.update("INSERT INTO audit_event (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, payload_json, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), tenantId, eventId, "Task", taskId, aggregateVersion, payload);
        return eventId;
    }
}
