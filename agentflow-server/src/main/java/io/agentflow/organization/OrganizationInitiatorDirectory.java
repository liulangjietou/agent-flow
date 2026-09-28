package io.agentflow.organization;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅向本人提供有效任职；提交时锁定目录并重新解析，页面选项不能代替当前授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class OrganizationInitiatorDirectory {
    private static final String APPOINTMENTS = """
            SELECT a.id, p.id AS person_id, p.subject, o.revision AS directory_revision,
                   l.id AS legal_entity_id, l.name AS legal_entity_name,
                   d.id AS department_id, d.name AS department_name,
                   j.id AS position_id, j.name AS position_name
            FROM organization_appointment a
            JOIN organization_directory o ON o.tenant_id=a.tenant_id
            JOIN organization_person p ON p.tenant_id=a.tenant_id AND p.id=a.person_id AND p.active=TRUE
            JOIN organization_unit d ON d.tenant_id=a.tenant_id AND d.id=a.department_id AND d.active=TRUE
            JOIN organization_unit j ON j.tenant_id=a.tenant_id AND j.id=a.position_id AND j.active=TRUE
            JOIN organization_unit l ON l.tenant_id=d.tenant_id AND l.id=d.legal_entity_id AND l.active=TRUE
            WHERE a.tenant_id=? AND p.subject=? AND a.active=TRUE
            """;
    private final JdbcTemplate jdbc;
    private final OrganizationRepository repository;

    /** 复用组织目录锁和租户数据源，不提供按任意主体查询的公开入口。 */
    public OrganizationInitiatorDirectory(JdbcTemplate jdbc, OrganizationRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    /** 有界读取当前账号的任职；申请人不需要具备审批资格或管理员权限。 */
    @Transactional(readOnly = true)
    public List<InitiatorContext> options(Actor actor, String afterId, int limit) {
        return jdbc.query(APPOINTMENTS + " AND a.id>? ORDER BY a.id LIMIT ?", this::map,
                actor.tenantId(), actor.userId(), afterId, limit + 1);
    }

    /** 目录锁持续到提交事务结束；他人、跨租户或停用任职统一拒绝。 */
    @Transactional
    public InitiatorContext snapshot(Actor actor, UUID appointmentId) {
        if (appointmentId == null) return null;
        return findCurrent(actor, appointmentId)
                .orElseThrow(() -> new DomainException("INITIATOR_APPOINTMENT_UNAVAILABLE", "Selected initiator appointment is not available"));
    }

    /** 后台预检把失效任职记录为检查结果，不通过事务代理抛异常后再尝试保存该结果。 */
    @Transactional
    public Optional<InitiatorContext> findCurrent(Actor actor, UUID appointmentId) {
        repository.lock(actor.tenantId());
        return jdbc.query(APPOINTMENTS + " AND a.id=?", this::map,
                        actor.tenantId(), actor.userId(), appointmentId.toString()).stream().findFirst();
    }

    private InitiatorContext map(ResultSet row, int index) throws SQLException {
        return new InitiatorContext(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("person_id")),
                row.getString("subject"), row.getLong("directory_revision"), UUID.fromString(row.getString("legal_entity_id")),
                row.getString("legal_entity_name"), UUID.fromString(row.getString("department_id")), row.getString("department_name"),
                UUID.fromString(row.getString("position_id")), row.getString("position_name"));
    }
}
