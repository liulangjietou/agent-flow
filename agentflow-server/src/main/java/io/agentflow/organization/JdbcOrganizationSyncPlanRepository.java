package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核对计划只追加，应用记录把批次与实际采用的完整计划绑定到同一租户。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcOrganizationSyncPlanRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 计划与组织修改共享数据源和统一 JSON 序列化。 */
    public JdbcOrganizationSyncPlanRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 只为仍处于相同目录及来源版本的已接收批次记录计划。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Saved create(OrganizationSyncPlan plan) {
        String body = json.write(plan), digest = digest(body);
        int count = jdbc.update("""
                INSERT INTO organization_sync_plan(tenant_id,id,batch_id,batch_version,source_version,directory_revision,
                    prepared_by,prepared_at,ready,digest,plan_json)
                SELECT b.tenant_id,?,b.id,?,?,?, ?,?,?,?,?
                FROM organization_sync_batch b JOIN organization_sync_source s ON s.tenant_id=b.tenant_id
                JOIN organization_directory d ON d.tenant_id=b.tenant_id
                WHERE b.tenant_id=? AND b.id=? AND b.status='RECEIVED' AND b.version=? AND s.version=?
                  AND s.applied_revision=b.after_revision AND d.revision=?
                """, plan.id().toString(), plan.batchVersion(), plan.sourceVersion(), plan.directoryRevision(), plan.preparedBy(),
                Timestamp.from(plan.preparedAt()), plan.ready(), digest, body, plan.tenantId(), plan.batchId().toString(),
                plan.batchVersion(), plan.sourceVersion(), plan.directoryRevision());
        changed(count); return new Saved(plan, digest);
    }

    /** 读取时校验原文摘要与查询字段，完整前后值不会依赖当前已变化的组织。 */
    public Optional<Saved> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM organization_sync_plan WHERE tenant_id=? AND id=?", (row, index) -> {
            String body = row.getString("plan_json"), digest = row.getString("digest");
            var plan = json.read(body, OrganizationSyncPlan.class);
            if (!digest(body).equals(digest) || !plan.tenantId().equals(row.getString("tenant_id"))
                    || !plan.id().toString().equals(row.getString("id")) || !plan.batchId().toString().equals(row.getString("batch_id"))
                    || plan.batchVersion() != row.getLong("batch_version") || plan.sourceVersion() != row.getLong("source_version")
                    || plan.directoryRevision() != row.getLong("directory_revision") || !plan.preparedBy().equals(row.getString("prepared_by"))
                    || !plan.preparedAt().equals(row.getTimestamp("prepared_at").toInstant()) || plan.ready() != row.getBoolean("ready")) throw inconsistent();
            return new Saved(plan, digest);
        }, tenant, id.toString()).stream().findFirst();
    }

    /** 应用轨迹与组织、批次、映射和游标一起提交，不接受跨批次或有冲突计划。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applied(Saved saved, OrganizationSyncBatch batch) {
        var plan = saved.plan(); var state = batch.state();
        if (!plan.ready() || state.status() != OrganizationSyncBatch.Status.APPLIED
                || !plan.tenantId().equals(batch.context().tenantId()) || !plan.batchId().equals(batch.context().id())
                || !saved.digest().equals(state.decision().applied().planDigest())
                || plan.directoryRevision() != state.decision().applied().directoryRevisionBefore()
                || plan.directoryRevision() + plan.changedRecords() != state.decision().applied().directoryRevisionAfter()) throw conflict();
        changed(jdbc.update("""
                INSERT INTO organization_sync_application(tenant_id,batch_id,plan_id,plan_digest)
                SELECT p.tenant_id,p.batch_id,p.id,p.digest FROM organization_sync_plan p
                JOIN organization_sync_batch b ON b.tenant_id=p.tenant_id AND b.id=p.batch_id
                WHERE p.tenant_id=? AND p.id=? AND p.batch_id=? AND p.digest=? AND p.ready=TRUE AND b.status='APPLIED'
                """, plan.tenantId(), plan.id().toString(), plan.batchId().toString(), saved.digest()));
    }

    /** 历史详情可回到实际采用的计划，不从最新来源重新解释已应用事实。 */
    public Optional<Saved> appliedPlan(String tenant, UUID batchId) {
        return jdbc.query("SELECT plan_id,plan_digest FROM organization_sync_application WHERE tenant_id=? AND batch_id=?", (row, index) -> {
            var saved = find(tenant, UUID.fromString(row.getString("plan_id"))).orElseThrow(JdbcOrganizationSyncPlanRepository::inconsistent);
            if (!saved.digest().equals(row.getString("plan_digest")) || !saved.plan().batchId().equals(batchId)) throw inconsistent();
            return saved;
        }, tenant, batchId.toString()).stream().findFirst();
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static void changed(int count) { if (count != 1) throw conflict(); }
    private static DomainException conflict() { return new DomainException("ORGANIZATION_SYNC_PLAN_STALE", "Organization synchronization plan no longer matches the current batch or directory"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted organization synchronization plan is inconsistent"); }

    /**
     * 摘要始终与服务端保存的完整计划同行。
     * @author owlzhangfq@gmail.com
     */
    public record Saved(OrganizationSyncPlan plan, String digest) { }
}
