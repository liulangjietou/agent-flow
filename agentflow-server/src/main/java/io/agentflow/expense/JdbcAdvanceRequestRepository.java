package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * 借款申请与审批状态分别持久化，每次变更追加不可覆盖的原始版本。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceRequestRepository implements AdvanceRequestRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 共用平台事务管理器，跨聚合编排可以一并回滚。 */
    public JdbcAdvanceRequestRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 所有跨聚合财务编排共用相同锁顺序，不能把锁留在某一种后台任务仓储。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, UUID requestId) {
        var application = jdbc.queryForList("SELECT application_id FROM advance_request WHERE tenant_id=? AND id=?", String.class, tenant, requestId.toString());
        if (application.isEmpty()) throw new DomainException("NOT_FOUND", "Advance request not found");
        jdbc.queryForList("SELECT id FROM approval_application WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, application.get(0));
        jdbc.queryForList("SELECT id FROM advance_request WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, requestId.toString());
    }

    @Override
    @Transactional
    public void create(AdvanceRequest advance, String actor) {
        requireAudit(actor, "CREATE");
        if (advance.version() != 1 || !advance.rounds().isEmpty()) throw conflict();
        int inserted = jdbc.update("""
                INSERT INTO advance_request(id,tenant_id,application_id,employee_id,version,state_json)
                SELECT ?,tenant_id,id,created_by,1,? FROM approval_application
                WHERE tenant_id=? AND id=? AND created_by=? AND business_type='ADVANCE_REQUEST' AND business_id=? AND status='DRAFT'
                """, advance.id().toString(), json.write(advance.state()), advance.tenantId(), advance.applicationId().toString(),
                advance.employeeId(), advance.id().toString());
        if (inserted != 1) throw conflict();
        append(advance, actor, "CREATE");
    }

    @Override
    @Transactional
    public void update(AdvanceRequest advance, long expectedVersion, String actor, String operation) {
        requireAudit(actor, operation);
        if (advance.version() != expectedVersion + 1) throw conflict();
        int updated = jdbc.update("""
                UPDATE advance_request SET version=?,state_json=?,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND id=? AND application_id=? AND employee_id=? AND version=?
                """, advance.version(), json.write(advance.state()), advance.tenantId(), advance.id().toString(),
                advance.applicationId().toString(), advance.employeeId(), expectedVersion);
        if (updated != 1) throw conflict();
        append(advance, actor, operation);
    }

    @Override
    public Optional<AdvanceRequest> find(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM advance_request WHERE tenant_id=? AND id=?", this::restore,
                tenantId, id.toString()).stream().findFirst();
    }

    private void append(AdvanceRequest advance, String actor, String operation) {
        jdbc.update("INSERT INTO advance_request_revision(tenant_id,request_id,request_version,actor_id,operation,state_json) VALUES(?,?,?,?,?,?)",
                advance.tenantId(), advance.id().toString(), advance.version(), actor, operation, json.write(advance.state()));
    }

    private AdvanceRequest restore(ResultSet row, int index) throws SQLException {
        var state = json.read(row.getString("state_json"), AdvanceRequest.State.class);
        if (!state.id().toString().equals(row.getString("id")) || !state.tenantId().equals(row.getString("tenant_id"))
                || !state.applicationId().toString().equals(row.getString("application_id"))
                || !state.employeeId().equals(row.getString("employee_id")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted advance request binding is inconsistent");
        }
        return AdvanceRequest.restore(state);
    }

    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_ADVANCE_REQUEST_AUDIT", "Advance request changes require an actor and operation");
        }
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Advance request binding or version has changed"); }
}
