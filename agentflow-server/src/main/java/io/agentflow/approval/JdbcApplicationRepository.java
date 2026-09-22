package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/H2 兼容的申请聚合仓储实现，所有查询均带租户条件。 */
@Repository
public class JdbcApplicationRepository implements ApplicationRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    /** 创建仓储。 */
    public JdbcApplicationRepository(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public Application save(Application application) {
        jdbcTemplate.update("""
                INSERT INTO approval_application
                (id, tenant_id, business_no, process_key, definition_version, created_by, title,
                 payload_json, status, round_no, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, application.id().toString(), application.tenantId(), application.businessNo(),
                application.processKey(), application.definitionVersion(), application.createdBy(), application.title(),
                jsonUtil.write(application.payload()), application.status().name(), application.roundNo(), application.version());
        return application;
    }

    @Override
    public Optional<Application> findById(String tenantId, UUID id) {
        List<Application> rows = jdbcTemplate.query("SELECT * FROM approval_application WHERE tenant_id=? AND id=?",
                this::map, tenantId, id.toString());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<Application> findByBusinessNo(String tenantId, String businessNo) {
        List<Application> rows = jdbcTemplate.query("SELECT * FROM approval_application WHERE tenant_id=? AND business_no=?",
                this::map, tenantId, businessNo);
        return rows.stream().findFirst();
    }

    @Override
    public Application update(Application application, long expectedVersion) {
        int updated = jdbcTemplate.update("""
                UPDATE approval_application SET status=?, round_no=?, version=?, title=?, payload_json=?, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND id=? AND version=?
                """, application.status().name(), application.roundNo(), application.version(), application.title(),
                jsonUtil.write(application.payload()), application.tenantId(), application.id().toString(), expectedVersion);
        if (updated != 1) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Application version has changed");
        }
        return application;
    }

    @Override
    public List<Application> findAll(String tenantId) {
        return jdbcTemplate.query("SELECT * FROM approval_application WHERE tenant_id=? ORDER BY updated_at DESC", this::map, tenantId);
    }

    private Application map(ResultSet resultSet, int rowNum) throws SQLException {
        return Application.restore(UUID.fromString(resultSet.getString("id")), resultSet.getString("tenant_id"),
                resultSet.getString("business_no"), resultSet.getString("process_key"),
                resultSet.getLong("definition_version"), resultSet.getString("created_by"),
                resultSet.getString("title"), jsonUtil.map(resultSet.getString("payload_json")),
                ApplicationStatus.valueOf(resultSet.getString("status")), resultSet.getInt("round_no"),
                resultSet.getLong("version"));
    }
}
