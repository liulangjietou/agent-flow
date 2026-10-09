package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.organization.mapper.ApprovalProxyRepositoryMapper;

import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 有期限代理的租户隔离存储；期限读取不依赖后台调度或引擎指派副本。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApprovalProxyRepository implements ApprovalProxyRepository {
    private final ApprovalProxyRepositoryMapper sqlMapper;

    /** 与组织变更审计和任务办理使用同一业务数据源。 */
    public JdbcApprovalProxyRepository(ApprovalProxyRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    @Override
    public void insert(String tenantId, ApprovalProxy proxy) {
        sqlMapper.insert(
                tenantId,
                proxy.id().toString(),
                proxy.definitionId().toString(),
                proxy.principalId().toString(),
                proxy.substituteId().toString(),
                Timestamp.from(proxy.startsAt()),
                Timestamp.from(proxy.endsAt()),
                proxy.reason(),
                proxy.createdBy(),
                Timestamp.from(proxy.createdAt()),
                DiagnosticContext.capture().traceId());
    }

    @Override
    public Optional<ApprovalProxy> find(String tenantId, UUID id) { return find(tenantId, id, false); }

    @Override
    public Optional<ApprovalProxy> lock(String tenantId, UUID id) { return find(tenantId, id, true); }

    private Optional<ApprovalProxy> find(String tenantId, UUID id, boolean lock) {
        return SqlRows.map(sqlMapper.findForAccess(tenantId, id.toString(), lock), this::map)
                .stream()
                .findFirst();
    }

    @Override
    public void revoke(
            String tenantId, UUID id, long expectedRevision, ApprovalProxy.Revocation revocation) {
        int changed =
                sqlMapper.revoke(
                        revocation.actor(),
                        revocation.reason(),
                        Timestamp.from(revocation.at()),
                        tenantId,
                        id.toString(),
                        expectedRevision);
        if (changed != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT", "Approval proxy revision has changed");
    }

    @Override
    public boolean overlaps(
            String tenantId,
            UUID definitionId,
            UUID principalId,
            Instant startsAt,
            Instant endsAt) {
        return Boolean.TRUE.equals(
                SqlRows.single(
                        sqlMapper.overlaps(
                                tenantId,
                                definitionId.toString(),
                                principalId.toString(),
                                Timestamp.from(endsAt),
                                Timestamp.from(startsAt))));
    }

    @Override
    public List<ApprovalProxy> list(String tenantId, UUID personId, String afterId, int limit) {
        if (personId == null)
            return SqlRows.map(sqlMapper.list(tenantId, afterId, limit + 1), this::map);
        return SqlRows.map(
                sqlMapper.list2(
                        tenantId, personId.toString(), personId.toString(), afterId, limit + 1),
                this::map);
    }

    @Override
    public List<ActiveProxy> activeForSubstitute(String tenantId, String subject, Instant now) {
        // JDBC 驱动可能把纳秒四舍五入到微秒；向下截断，防止提前进入下一授权区间。
        Timestamp observedAt =
                Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return SqlRows.map(
                sqlMapper.activeForSubstitute(
                        tenantId, subject, observedAt, observedAt, observedAt),
                row -> new ActiveProxy(map(row), row.getString("principal_subject")));
    }

    private ApprovalProxy map(SqlRow row) {
        Timestamp revokedAt = row.getTimestamp("revoked_at");
        var revocation = revokedAt == null ? null : new ApprovalProxy.Revocation(row.getString("revoked_by"),
                row.getString("revoked_reason"), revokedAt.toInstant());
        return new ApprovalProxy(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("definition_id")),
                UUID.fromString(row.getString("principal_id")), UUID.fromString(row.getString("substitute_id")),
                row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(), row.getString("reason"),
                row.getString("created_by"), row.getTimestamp("created_at").toInstant(), row.getLong("revision"), revocation);
    }
}
