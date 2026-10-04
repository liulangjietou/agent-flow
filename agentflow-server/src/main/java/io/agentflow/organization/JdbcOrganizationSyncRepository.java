package io.agentflow.organization;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步来源、批次与稳定映射持久化；应用服务把它们与原组织修改置于同一事务。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcOrganizationSyncRepository {
    private static final int SCAN_LIMIT = 10;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 使用原组织数据库，不单独持有认证数据或外部调用连接。 */
    public JdbcOrganizationSyncRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 来源注册属于管理员的组织事务；同租户不能静默切换来源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(OrganizationSyncSource source) {
        if (source.version() != 1) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO organization_sync_source(tenant_id,source_key,applied_revision,version,registered_by,registered_at)
                    VALUES(?,?,0,1,?,?)
                    """, source.tenantId(), source.sourceKey(), source.registeredBy(), Timestamp.from(source.registeredAt()));
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("ORGANIZATION_SYNC_SOURCE_CONFLICT", "A synchronization source is already registered for this tenant");
        }
    }

    /** 读取已注册来源；最后应用批次必须与游标相符。 */
    public Optional<OrganizationSyncSource> source(String tenant) {
        return jdbc.query("SELECT * FROM organization_sync_source WHERE tenant_id=?", (row, index) -> {
            var result = new OrganizationSyncSource(row.getString("tenant_id"), row.getString("source_key"), row.getLong("applied_revision"),
                    row.getLong("version"), uuid(row.getString("last_applied_batch_id")), row.getString("registered_by"), row.getTimestamp("registered_at").toInstant());
            if (result.lastAppliedBatchId() != null) {
                var batch = find(tenant, result.lastAppliedBatchId()).orElseThrow(JdbcOrganizationSyncRepository::inconsistent);
                if (batch.state().status() != OrganizationSyncBatch.Status.APPLIED || !batch.context().sourceKey().equals(result.sourceKey())
                        || batch.state().delta().revision() != result.appliedRevision()) throw inconsistent();
            }
            return result;
        }, tenant).stream().findFirst();
    }

    /** 应用用例按组织目录、来源、批次顺序持锁，任何网络调用都在这些锁之外。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockSource(String tenant) {
        return !jdbc.queryForList("SELECT tenant_id FROM organization_sync_source WHERE tenant_id=? FOR UPDATE", String.class, tenant).isEmpty();
    }

    /** 仅在同事务已有 APPLIED 批次且其起始游标仍匹配时推进来源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(OrganizationSyncSource source, long expectedVersion) {
        if (source.version() != expectedVersion + 1 || source.lastAppliedBatchId() == null) throw conflict();
        changed(jdbc.update("""
                UPDATE organization_sync_source SET applied_revision=?,version=?,last_applied_batch_id=?
                WHERE tenant_id=? AND source_key=? AND version=? AND registered_by=? AND registered_at=?
                  AND EXISTS(SELECT 1 FROM organization_sync_batch b WHERE b.tenant_id=organization_sync_source.tenant_id
                    AND b.id=? AND b.source_key=organization_sync_source.source_key AND b.status='APPLIED'
                    AND b.after_revision=organization_sync_source.applied_revision AND b.received_revision=?)
                """, source.appliedRevision(), source.version(), id(source.lastAppliedBatchId()), source.tenantId(), source.sourceKey(),
                expectedVersion, source.registeredBy(), Timestamp.from(source.registeredAt()), id(source.lastAppliedBatchId()), source.appliedRevision()));
    }

    /** 队列绑定当前游标；数据库唯一键也覆盖待人工核对的批次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(OrganizationSyncBatch batch) {
        var context = batch.context();
        if (batch.state().status() != OrganizationSyncBatch.Status.QUEUED) throw conflict();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO organization_sync_batch(tenant_id,id,source_key,after_revision,requested_by,retry_of,status,version,
                        pending_tenant_id,context_json,state_json,created_at)
                    SELECT s.tenant_id,?,s.source_key,s.applied_revision,?,?,'QUEUED',1,s.tenant_id,?,?,?
                    FROM organization_sync_source s WHERE s.tenant_id=? AND s.source_key=? AND s.applied_revision=?
                      AND (CAST(? AS VARCHAR(36)) IS NULL OR EXISTS(SELECT 1 FROM organization_sync_batch old
                        WHERE old.tenant_id=s.tenant_id AND old.id=? AND old.source_key=s.source_key AND old.status IN ('FAILED','CANCELLED')))
                    """, id(context.id()), context.requestedBy(), id(context.retryOf()), json.write(context), json.write(batch.state()), Timestamp.from(context.createdAt()),
                    context.tenantId(), context.sourceKey(), context.afterRevision(), id(context.retryOf()), id(context.retryOf()));
            changed(inserted);
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("ORGANIZATION_SYNC_BATCH_ACTIVE", "A synchronization batch is already pending for this tenant");
        }
        append(batch);
    }

    /** 转换与追加轨迹同时保存，比较原上下文和原状态以拒绝迟到结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(OrganizationSyncBatch batch, long expectedVersion) {
        var context = batch.context(); var state = batch.state();
        if (state.version() != expectedVersion + 1) throw conflict();
        String previous = switch (state.status()) {
            case FETCHING -> "QUEUED";
            case RECEIVED, FAILED -> "FETCHING";
            case APPLIED -> "RECEIVED";
            case CANCELLED -> state.delta() != null ? "RECEIVED" : state.startedAt() != null ? "FETCHING" : "QUEUED";
            case QUEUED -> throw conflict();
        };
        changed(jdbc.update("""
                UPDATE organization_sync_batch SET status=?,version=?,pending_tenant_id=?,state_json=?,lease_until=?,received_revision=?
                WHERE tenant_id=? AND id=? AND status=? AND version=? AND context_json=?
                  AND (?<>'APPLIED' OR EXISTS(SELECT 1 FROM organization_sync_source s WHERE s.tenant_id=organization_sync_batch.tenant_id
                    AND s.source_key=organization_sync_batch.source_key AND s.applied_revision=organization_sync_batch.after_revision))
                """, state.status().name(), state.version(), batch.pending() ? context.tenantId() : null, json.write(state),
                state.status() == OrganizationSyncBatch.Status.FETCHING ? Timestamp.from(state.leaseUntil()) : null,
                state.delta() == null ? null : state.delta().revision(), context.tenantId(), id(context.id()), previous, expectedVersion, json.write(context), state.status().name()));
        append(batch);
    }

    /** 所有读取均限定租户，重新构造聚合时核对可查询列与原文。 */
    public Optional<OrganizationSyncBatch> find(String tenant, UUID batchId) {
        return jdbc.query("SELECT * FROM organization_sync_batch WHERE tenant_id=? AND id=?", this::restore, tenant, id(batchId)).stream().findFirst();
    }

    /** 工作器和人工决定在来源锁之后取得批次锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockBatch(String tenant, UUID batchId) {
        return !jdbc.queryForList("SELECT id FROM organization_sync_batch WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, id(batchId)).isEmpty();
    }

    /** 已接收数据等待管理员，不会由后台重复拉取。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM organization_sync_batch WHERE status='QUEUED' OR (status='FETCHING' AND lease_until<=?)
                ORDER BY created_at,id LIMIT ?
                """, (row, index) -> new Candidate(row.getString("tenant_id"), uuid(row.getString("id"))), Timestamp.from(now), SCAN_LIMIT);
    }

    /** 有界历史索引不包含人员主体或完整来源正文。 */
    public Page page(String tenant, int page, int size) {
        var items = jdbc.query("""
                SELECT id,source_key,after_revision,received_revision,status,version,requested_by,created_at
                FROM organization_sync_batch WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new Summary(uuid(row.getString("id")), row.getString("source_key"), row.getLong("after_revision"),
                row.getObject("received_revision", Long.class), OrganizationSyncBatch.Status.valueOf(row.getString("status")),
                row.getLong("version"), row.getString("requested_by"), row.getTimestamp("created_at").toInstant()), tenant, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM organization_sync_batch WHERE tenant_id=?", Long.class, tenant);
        return new Page(items, total, page, size);
    }

    /** 稳定映射必须与相同版本的不可变历史原文一致。 */
    public Optional<OrganizationSyncBinding> binding(String tenant, OrganizationSyncKey key) {
        return jdbc.query("""
                SELECT b.*,c.snapshot_json FROM organization_sync_binding b LEFT JOIN organization_sync_binding_change c
                  ON c.tenant_id=b.tenant_id AND c.kind=b.kind AND c.external_id=b.external_id AND c.binding_version=b.version
                WHERE b.tenant_id=? AND b.kind=? AND b.external_id=?
                """, (row, index) -> {
            var value = new OrganizationSyncBinding(row.getString("tenant_id"), row.getString("source_key"),
                    new OrganizationSyncKey(OrganizationSyncKey.Kind.valueOf(row.getString("kind")), row.getString("external_id")), uuid(row.getString("local_id")),
                    row.getLong("local_revision"), row.getLong("source_revision"), row.getLong("version"), uuid(row.getString("applied_batch_id")), row.getTimestamp("updated_at").toInstant());
            String saved = row.getString("snapshot_json");
            if (saved == null || !value.equals(json.read(saved, OrganizationSyncBinding.class))) throw inconsistent();
            return value;
        }, tenant, key.kind().name(), key.externalId()).stream().findFirst();
    }

    /** 保存当前实体修订与来源批次的对应关系；不允许改绑已有来源标识。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(OrganizationSyncBinding value, long expectedVersion) {
        if (value.version() != expectedVersion + 1) throw conflict();
        String kind = value.key().kind().name(); String local = id(value.localId());
        String table = switch (value.key().kind()) {
            case PERSON -> "organization_person";
            case APPOINTMENT -> "organization_appointment";
            default -> "organization_unit";
        };
        String kindFilter = table.equals("organization_unit") ? " AND l.kind='" + kind + "'" : "";
        Long matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM organization_sync_batch b JOIN %s l ON l.tenant_id=b.tenant_id
                WHERE b.tenant_id=? AND b.id=? AND b.source_key=? AND b.status='APPLIED' AND b.received_revision=? AND l.id=? AND l.revision=?
                """.formatted(table) + kindFilter, Long.class, value.tenantId(), id(value.appliedBatchId()), value.sourceKey(), value.sourceRevision(), local, value.localRevision());
        if (matches == null || matches != 1) throw conflict();
        try {
            if (expectedVersion == 0) {
                jdbc.update("""
                        INSERT INTO organization_sync_binding(tenant_id,source_key,kind,external_id,local_id,unit_id,person_id,appointment_id,
                            local_revision,source_revision,version,applied_batch_id,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?)
                        """, value.tenantId(), value.sourceKey(), kind, value.key().externalId(), local,
                        table.equals("organization_unit") ? local : null, table.equals("organization_person") ? local : null,
                        table.equals("organization_appointment") ? local : null, value.localRevision(), value.sourceRevision(), id(value.appliedBatchId()), Timestamp.from(value.updatedAt()));
            } else changed(jdbc.update("""
                    UPDATE organization_sync_binding SET local_revision=?,source_revision=?,version=?,applied_batch_id=?,updated_at=?
                    WHERE tenant_id=? AND source_key=? AND kind=? AND external_id=? AND local_id=? AND version=?
                      AND local_revision<=? AND source_revision<? AND updated_at<=?
                    """, value.localRevision(), value.sourceRevision(), value.version(), id(value.appliedBatchId()), Timestamp.from(value.updatedAt()), value.tenantId(), value.sourceKey(),
                    kind, value.key().externalId(), local, expectedVersion, value.localRevision(), value.sourceRevision(), Timestamp.from(value.updatedAt())));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("ORGANIZATION_SYNC_BINDING_CONFLICT", "The source key or local organization entity is already bound"); }
        jdbc.update("INSERT INTO organization_sync_binding_change(tenant_id,kind,external_id,binding_version,batch_id,snapshot_json) VALUES(?,?,?,?,?,?)",
                value.tenantId(), kind, value.key().externalId(), value.version(), id(value.appliedBatchId()), json.write(value));
    }

    private void append(OrganizationSyncBatch batch) {
        jdbc.update("INSERT INTO organization_sync_transition(tenant_id,batch_id,batch_version,status,state_json) VALUES(?,?,?,?,?)",
                batch.context().tenantId(), id(batch.context().id()), batch.state().version(), batch.state().status().name(), json.write(batch.state()));
    }

    private OrganizationSyncBatch restore(ResultSet row, int index) throws SQLException {
        var context = json.read(row.getString("context_json"), OrganizationSyncBatch.Context.class);
        var state = json.read(row.getString("state_json"), OrganizationSyncBatch.State.class);
        var batch = OrganizationSyncBatch.restore(context, state); var lease = row.getTimestamp("lease_until");
        if (!context.tenantId().equals(row.getString("tenant_id")) || !id(context.id()).equals(row.getString("id"))
                || !context.sourceKey().equals(row.getString("source_key")) || context.afterRevision() != row.getLong("after_revision")
                || !context.requestedBy().equals(row.getString("requested_by")) || !Objects.equals(id(context.retryOf()), row.getString("retry_of"))
                || !state.status().name().equals(row.getString("status")) || state.version() != row.getLong("version")
                || !context.createdAt().equals(row.getTimestamp("created_at").toInstant())
                || !Objects.equals(state.delta() == null ? null : state.delta().revision(), row.getObject("received_revision", Long.class))
                || !Objects.equals(batch.pending() ? context.tenantId() : null, row.getString("pending_tenant_id"))
                || !Objects.equals(lease == null ? null : lease.toInstant(), state.status() == OrganizationSyncBatch.Status.FETCHING ? state.leaseUntil() : null)) throw inconsistent();
        return batch;
    }

    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static void changed(int count) { if (count != 1) throw conflict(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Organization synchronization state changed before persistence"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted organization synchronization binding is inconsistent"); }

    /**
     * 调度扫描不返回目录正文。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
    /**
     * 批次索引展示原游标、状态及具名发起者。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, String sourceKey, long afterRevision, Long receivedRevision, OrganizationSyncBatch.Status status,
                          long version, String requestedBy, Instant createdAt) { }
    /**
     * 管理入口负责统一验证分页参数。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) { }
}
