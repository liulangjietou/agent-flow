package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原始发送上下文、运行状态、租约与追加轨迹使用同一数据源。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDraftAssistRunRepository implements DraftAssistRunRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 不保存凭据，不将建议或源正文写入索引列。 */
    public JdbcDraftAssistRunRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    @Override
    @Transactional
    public void create(DraftAssistRun run) {
        var context = run.context();
        if (run.state().status() != DraftAssistRun.Status.QUEUED || run.state().version() != 1) throw conflict();
        int changed = jdbc.update("""
                INSERT INTO agent_draft_assist_run(id,tenant_id,application_id,application_version,status,version,context_json,state_json,created_at,trace_id)
                SELECT ?,tenant_id,id,version,'QUEUED',1,?,?,?,? FROM approval_application
                WHERE tenant_id=? AND id=? AND version=? AND created_by=?
                """, context.id().toString(), json.write(context), json.write(run.state()), Timestamp.from(context.createdAt()), DiagnosticContext.capture().traceId(),
                context.tenantId(), context.input().applicationId().toString(), context.input().applicationVersion(), context.requestedBy());
        if (changed != 1) throw new DomainException("AGENT_INPUT_CHANGED", "Draft changed before queue persistence");
        append(run);
    }

    @Override
    @Transactional
    public void update(DraftAssistRun run, long expectedVersion) {
        var state = run.state(); var context = run.context();
        if (state.version() != expectedVersion + 1) throw conflict();
        String previous = switch (state.status()) {
            case RUNNING -> "QUEUED";
            case COMPLETED, FAILED -> "RUNNING";
            case ADOPTED, DISMISSED -> "COMPLETED";
            case QUEUED -> throw conflict();
        };
        int changed = jdbc.update("""
                UPDATE agent_draft_assist_run SET status=?,version=?,state_json=?
                WHERE tenant_id=? AND id=? AND version=? AND status=? AND context_json=?
                """, state.status().name(), state.version(), json.write(state), context.tenantId(), context.id().toString(),
                expectedVersion, previous, json.write(context));
        if (changed != 1) throw conflict();
        append(run);
    }

    @Override
    public Optional<DraftAssistRun> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM agent_draft_assist_run WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 锁顺序由应用层保持为申请在前、运行在后；锁不得脱离事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String tenant, UUID id) {
        return !jdbc.queryForList("SELECT id FROM agent_draft_assist_run WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, id.toString()).isEmpty();
    }

    /** 待执行与超时记录有界扫描；历史建议不重复执行。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no FROM agent_draft_assist_run q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                WHERE q.status='QUEUED' OR (q.status='RUNNING' AND q.lease_until<=?)
                ORDER BY q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no")), Timestamp.from(now));
    }

    /** 同申请排队前在申请锁内检查，避免不同幂等键产生同时执行的建议。 */
    public boolean active(String tenant, UUID applicationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_draft_assist_run WHERE tenant_id=? AND application_id=? AND status IN ('QUEUED','RUNNING')",
                Long.class, tenant, applicationId.toString()) > 0;
    }

    /** 进程丢失的租约只结算失败，不推断模型没有收到内容。 */
    public Instant lease(String tenant, UUID id) {
        return jdbc.queryForObject("SELECT lease_until FROM agent_draft_assist_run WHERE tenant_id=? AND id=?",
                (row, index) -> { var value = row.getTimestamp(1); return value == null ? null : value.toInstant(); }, tenant, id.toString());
    }

    /** 领取或结算事务内更新租约。 */
    public void lease(String tenant, UUID id, Instant until) {
        jdbc.update("UPDATE agent_draft_assist_run SET lease_until=? WHERE tenant_id=? AND id=?",
                until == null ? null : Timestamp.from(until), tenant, id.toString());
    }

    /** 分页索引不读取或投影原始正文，详情授权由应用层另外核对。 */
    public Page page(String tenant, UUID applicationId, int page, int size) {
        var rows = jdbc.query("""
                SELECT id,application_version,status,version,created_at FROM agent_draft_assist_run
                WHERE tenant_id=? AND application_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new Summary(UUID.fromString(row.getString("id")), row.getLong("application_version"),
                DraftAssistRun.Status.valueOf(row.getString("status")), row.getLong("version"), row.getTimestamp("created_at").toInstant()),
                tenant, applicationId.toString(), size, (long) page * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_draft_assist_run WHERE tenant_id=? AND application_id=?", Long.class, tenant, applicationId.toString());
        return new Page(rows, total, page, size);
    }
    private void append(DraftAssistRun run) {
        jdbc.update("INSERT INTO agent_draft_assist_transition(tenant_id,run_id,run_version,status,state_json) VALUES(?,?,?,?,?)",
                run.context().tenantId(), run.context().id().toString(), run.state().version(), run.state().status().name(), json.write(run.state()));
    }
    private DraftAssistRun restore(ResultSet row, int index) throws SQLException {
        var context = json.read(row.getString("context_json"), DraftAssistRun.Context.class);
        var state = json.read(row.getString("state_json"), DraftAssistRun.State.class);
        if (!context.id().toString().equals(row.getString("id")) || !context.tenantId().equals(row.getString("tenant_id"))
                || !context.input().applicationId().toString().equals(row.getString("application_id"))
                || context.input().applicationVersion() != row.getLong("application_version")
                || !state.status().name().equals(row.getString("status")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted draft assist context is inconsistent");
        }
        return DraftAssistRun.restore(context, state);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Draft assist changed before persistence"); }
    /**
     * 工作扫描只返回不透明标识。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo) {
        /** 没有业务关联的历史调用保持缺失事实，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null); }
    }
    /**
     * 申请人索引不含任何提示或表单正文。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, long applicationVersion, DraftAssistRun.Status status, long version, Instant createdAt) { }
    /**
     * 有界分页保留总数，历史运行不因新建议被覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) { }
}
