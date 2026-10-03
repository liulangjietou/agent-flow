package io.agentflow.onboarding;

import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次配置凭据与操作审计原子追加；数据库唯一键和租户外键保留来源完整性。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcTenantInitializationRepository implements TenantInitializationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 使用既有业务数据源及统一 JSON 组件。 */
    public JdbcTenantInitializationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    public Optional<TenantInitialization> find(String tenantId) {
        return jdbc.query("SELECT snapshot_json FROM tenant_initialization WHERE tenant_id=?",
                (row, index) -> json.read(row.getString("snapshot_json"), TenantInitialization.class), tenantId).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(TenantInitialization value) {
        jdbc.update("""
                INSERT INTO tenant_initialization
                (tenant_id,id,workspace_name,initialized_by,initialized_at,administrator_person_id,
                 administrator_appointment_id,calendar_id,calendar_revision,snapshot_json) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, value.tenantId(), value.id().toString(), value.workspaceName(), value.initializedBy(), Timestamp.from(value.initializedAt()),
                value.organization().personId().toString(), value.organization().appointmentId().toString(), value.calendar().id().toString(),
                value.calendar().revision(), json.write(value));
        // 统一检索只需操作来源，详细快照留在受限初始化入口，不复制任何通知地址或凭据。
        jdbc.update("""
                INSERT INTO audit_event
                (id,tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,action,actor_id,payload_json,occurred_at)
                VALUES (?,?,?,'TenantInitialization',?,1,'TENANT_INITIALIZE',?,?,?)
                """, UUID.randomUUID().toString(), value.tenantId(), UUID.randomUUID().toString(), value.id().toString(), value.initializedBy(),
                json.write(Map.of("workspaceName", value.workspaceName(), "appointmentId", value.organization().appointmentId(),
                        "calendarId", value.calendar().id(), "calendarRevision", value.calendar().revision())), Timestamp.from(value.initializedAt()));
    }
}
