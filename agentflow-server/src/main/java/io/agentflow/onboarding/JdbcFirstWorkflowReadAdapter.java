package io.agentflow.onboarding;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 按版本聚合已有轮次，避免其他租户、旧版本或当前申请状态冒充引导完成证据。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcFirstWorkflowReadAdapter implements FirstWorkflowReadPort {
    private static final String ROUND_FROM = """
            FROM approval_submission_round r JOIN approval_application a
              ON a.tenant_id=r.tenant_id AND a.id=r.application_id
            WHERE r.tenant_id=? AND a.process_key=? AND r.definition_version=?
            """;
    private final JdbcTemplate jdbc;

    /** 使用审批数据源查询事实，不修改业务数据或初始化账号。 */
    public JdbcFirstWorkflowReadAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report read(String tenantId, UUID definitionId, Instant checkedAt) {
        String select = "SELECT id,process_key,name,version,status FROM approval_definition WHERE tenant_id=?";
        List<Definition> definitions = definitionId == null
                ? jdbc.query(select + " ORDER BY updated_at DESC,id LIMIT 1", this::definition, tenantId)
                : jdbc.query(select + " AND id=?", this::definition, tenantId, definitionId.toString());
        if (definitions.isEmpty()) {
            if (definitionId != null) throw new DomainException("NOT_FOUND", "Process definition was not found");
            return new Report(checkedAt, null, 0, 0, 0, null, null);
        }
        Definition definition = definitions.get(0);
        if (definition.version() == 0) return new Report(checkedAt, definition, 0, 0, 0, null, null);
        Object[] parameters = {tenantId, definition.key(), definition.version()};
        long submitted = jdbc.queryForObject("SELECT COUNT(*) " + ROUND_FROM, Long.class, parameters);
        long approved = jdbc.queryForObject("SELECT COUNT(*) " + ROUND_FROM + " AND r.status='APPROVED'", Long.class, parameters);
        long unrecorded = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(0,a.round_no-(SELECT COUNT(*) FROM approval_submission_round r
                  WHERE r.tenant_id=a.tenant_id AND r.application_id=a.id AND r.definition_version=a.definition_version))),0)
                FROM approval_application a WHERE a.tenant_id=? AND a.process_key=? AND a.definition_version=?
                """, Long.class, parameters);
        return new Report(checkedAt, definition, submitted, approved, unrecorded, evidence(parameters, false), evidence(parameters, true));
    }

    private Definition definition(ResultSet row, int index) throws SQLException {
        return new Definition(UUID.fromString(row.getString("id")), row.getString("process_key"), row.getString("name"),
                row.getLong("version"), row.getString("status"));
    }

    private Evidence evidence(Object[] parameters, boolean approved) {
        var evidence = jdbc.query("""
                SELECT r.application_id,a.business_no,r.title,r.round_no,r.status,r.submitted_at,r.completed_at
                """ + ROUND_FROM + (approved ? " AND r.status='APPROVED' ORDER BY r.completed_at DESC," : " ORDER BY ")
                + "r.submitted_at DESC,r.application_id,r.round_no DESC LIMIT 1", (row, index) -> {
            var completed = row.getTimestamp("completed_at");
            return new Evidence(row.getString("application_id"), row.getString("business_no"), row.getString("title"),
                    row.getInt("round_no"), row.getString("status"), row.getTimestamp("submitted_at").toInstant(),
                    completed == null ? null : completed.toInstant());
        }, parameters);
        return evidence.isEmpty() ? null : evidence.get(0);
    }
}
