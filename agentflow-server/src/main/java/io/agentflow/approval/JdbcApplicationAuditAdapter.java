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
    public void recordWithdrawal(String tenantId, UUID applicationId, long aggregateVersion, int roundNo,
                                 String processInstanceId, String actor, String comment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "WITHDRAW");
        payload.put("actor", actor);
        payload.put("roundNo", roundNo);
        payload.put("processInstanceId", processInstanceId);
        payload.put("comment", comment);
        jdbc.update("""
                INSERT INTO audit_event
                (id, tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version, payload_json, occurred_at)
                VALUES (?, ?, ?, 'Application', ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), tenantId, UUID.randomUUID().toString(),
                applicationId.toString(), aggregateVersion, json.write(payload));
    }
}
