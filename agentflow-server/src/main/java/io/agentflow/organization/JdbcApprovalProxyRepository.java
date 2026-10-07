package io.agentflow.organization;

import io.agentflow.observability.DiagnosticContext;
import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 有期限代理的租户隔离存储；期限读取不依赖后台调度或引擎指派副本。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApprovalProxyRepository implements ApprovalProxyRepository {
    private final JdbcTemplate jdbc;
    private final String lockClause;

    /** 与组织变更审计和任务办理使用同一业务数据源。 */
    public JdbcApprovalProxyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 代理主键不可变；PostgreSQL 只排他锁定非键值变更，让其他申请保存通知外键时仍可取得 KEY SHARE。
        // 办理与撤销继续在同一代理行上互斥，H2 使用其支持的 FOR UPDATE。
        this.lockClause = jdbc.execute((ConnectionCallback<String>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()) ? " FOR NO KEY UPDATE" : " FOR UPDATE");
    }

    @Override
    public void insert(String tenantId, ApprovalProxy proxy) {
        jdbc.update("""
                INSERT INTO organization_approval_proxy
                (tenant_id,id,definition_id,principal_id,substitute_id,starts_at,ends_at,reason,created_by,created_at,revision,trace_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,1,?)
                """, tenantId, proxy.id().toString(), proxy.definitionId().toString(), proxy.principalId().toString(),
                proxy.substituteId().toString(), Timestamp.from(proxy.startsAt()), Timestamp.from(proxy.endsAt()),
                proxy.reason(), proxy.createdBy(), Timestamp.from(proxy.createdAt()), DiagnosticContext.capture().traceId());
    }

    @Override
    public Optional<ApprovalProxy> find(String tenantId, UUID id) { return find(tenantId, id, false); }

    @Override
    public Optional<ApprovalProxy> lock(String tenantId, UUID id) { return find(tenantId, id, true); }

    private Optional<ApprovalProxy> find(String tenantId, UUID id, boolean lock) {
        return jdbc.query("SELECT p.* FROM organization_approval_proxy p WHERE tenant_id=? AND id=?" + (lock ? lockClause : ""),
                this::map, tenantId, id.toString()).stream().findFirst();
    }

    @Override
    public void revoke(String tenantId, UUID id, long expectedRevision, ApprovalProxy.Revocation revocation) {
        int changed = jdbc.update("""
                UPDATE organization_approval_proxy SET revoked_by=?,revoked_reason=?,revoked_at=?,revision=revision+1
                WHERE tenant_id=? AND id=? AND revision=? AND revoked_at IS NULL
                """, revocation.actor(), revocation.reason(), Timestamp.from(revocation.at()), tenantId, id.toString(), expectedRevision);
        if (changed != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Approval proxy revision has changed");
    }

    @Override
    public boolean overlaps(String tenantId, UUID definitionId, UUID principalId, Instant startsAt, Instant endsAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM organization_approval_proxy WHERE tenant_id=? AND definition_id=?
                AND principal_id=? AND revoked_at IS NULL AND starts_at<? AND ends_at>?)
                """, Boolean.class, tenantId, definitionId.toString(), principalId.toString(), Timestamp.from(endsAt), Timestamp.from(startsAt)));
    }

    @Override
    public List<ApprovalProxy> list(String tenantId, UUID personId, String afterId, int limit) {
        if (personId == null) return jdbc.query("SELECT p.* FROM organization_approval_proxy p WHERE tenant_id=? AND id>? ORDER BY id LIMIT ?",
                this::map, tenantId, afterId, limit + 1);
        return jdbc.query("SELECT p.* FROM organization_approval_proxy p WHERE tenant_id=? AND (principal_id=? OR substitute_id=?) AND id>? ORDER BY id LIMIT ?",
                this::map, tenantId, personId.toString(), personId.toString(), afterId, limit + 1);
    }

    @Override
    public List<ActiveProxy> activeForSubstitute(String tenantId, String subject, Instant now) {
        // JDBC 驱动可能把纳秒四舍五入到微秒；向下截断，防止提前进入下一授权区间。
        Timestamp observedAt = Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return jdbc.query("""
                SELECT p.*,principal.subject AS principal_subject FROM organization_approval_proxy p
                JOIN organization_person principal ON principal.tenant_id=p.tenant_id AND principal.id=p.principal_id
                JOIN organization_person substitute ON substitute.tenant_id=p.tenant_id AND substitute.id=p.substitute_id
                WHERE p.tenant_id=? AND substitute.subject=? AND principal.active=TRUE AND principal.approval_eligible=TRUE
                AND substitute.active=TRUE AND substitute.approval_eligible=TRUE
                AND p.revoked_at IS NULL AND p.created_at<=? AND p.starts_at<=? AND p.ends_at>?
                ORDER BY p.id
                """, (row, index) -> new ActiveProxy(map(row, index), row.getString("principal_subject")),
                tenantId, subject, observedAt, observedAt, observedAt);
    }

    private ApprovalProxy map(ResultSet row, int index) throws SQLException {
        Timestamp revokedAt = row.getTimestamp("revoked_at");
        var revocation = revokedAt == null ? null : new ApprovalProxy.Revocation(row.getString("revoked_by"),
                row.getString("revoked_reason"), revokedAt.toInstant());
        return new ApprovalProxy(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("definition_id")),
                UUID.fromString(row.getString("principal_id")), UUID.fromString(row.getString("substitute_id")),
                row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(), row.getString("reason"),
                row.getString("created_by"), row.getTimestamp("created_at").toInstant(), row.getLong("revision"), revocation);
    }
}
