package io.agentflow.approval;

import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.JsonUtil;
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

    /** 使用申请事务共用的数据源记录审计。 */
    public JdbcApplicationAuditAdapter(JdbcTemplate jdbc, JsonUtil json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(ApplicationOperation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
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
                (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, application_id, action, payload_json, occurred_at)
                VALUES (?, ?, ?, 'Application', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), operation.tenantId(), UUID.randomUUID().toString(),
                operation.applicationId().toString(), operation.aggregateVersion(), operation.applicationId().toString(),
                operation.action().name(), json.write(payload));
    }
}
