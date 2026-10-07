package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
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
 * 报销填报建议绑定本人、原双版本和原发送内容，状态与逐项确认轨迹共同提交。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseDraftAssistRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 只持久化助手聚合，费用和审批的写入不属于本仓储。 */
    public JdbcExpenseDraftAssistRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 原来源仍是同一可编辑报销版本时才入队，活动唯一键阻止并发重复执行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseDraftAssistRun run) {
        var context = run.context(); var input = context.input();
        if (run.state().status() != ExpenseDraftAssistRun.Status.QUEUED) throw conflict();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO agent_expense_draft_run(tenant_id,id,report_id,application_id,requested_by,
                        application_version,financial_version,status,version,active_report_id,context_json,state_json,created_at,trace_id)
                    SELECT r.tenant_id,?,r.id,r.application_id,r.employee_id,a.version,r.version,'QUEUED',1,r.id,?,?,?,?
                    FROM expense_report r JOIN approval_application a ON a.tenant_id=r.tenant_id AND a.id=r.application_id
                    WHERE r.tenant_id=? AND r.id=? AND r.application_id=? AND r.employee_id=? AND a.created_by=r.employee_id
                        AND a.version=? AND r.version=? AND a.status IN ('DRAFT','RETURNED','WITHDRAWN')
                        AND a.business_type='EXPENSE' AND a.business_id=r.id
                    """, context.id().toString(), json.write(context), json.write(run.state()), Timestamp.from(context.createdAt()), DiagnosticContext.capture().traceId(),
                    context.tenantId(), input.reportId().toString(), input.applicationId().toString(), context.requestedBy(),
                    input.applicationVersion(), input.financialVersion());
            if (inserted != 1) throw new DomainException("AGENT_INPUT_CHANGED", "Expense draft changed before persistence");
        } catch (DuplicateKeyException active) { throw new DomainException("AGENT_RUN_ACTIVE", "Expense report already has active draft assistance"); }
        append(run);
    }

    /** 比较原版本和完整上下文，确认记录失败时不能单独留下已确认状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseDraftAssistRun run, long expectedVersion) {
        var context = run.context(); var state = run.state();
        if (state.version() != expectedVersion + 1) throw conflict();
        String previous = switch (state.status()) {
            case RUNNING -> "QUEUED";
            case COMPLETED, FAILED -> "RUNNING";
            case CONFIRMED, DISMISSED -> "COMPLETED";
            case QUEUED -> throw conflict();
        };
        int changed = jdbc.update("""
                UPDATE agent_expense_draft_run SET status=?,version=?,state_json=?,active_report_id=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND status=? AND context_json=?
                """, state.status().name(), state.version(), json.write(state), run.active() ? context.input().reportId().toString() : null,
                state.status() == ExpenseDraftAssistRun.Status.RUNNING ? Timestamp.from(state.leaseUntil()) : null,
                context.tenantId(), context.id().toString(), expectedVersion, previous, json.write(context));
        if (changed != 1) throw conflict();
        append(run);
    }

    /** 租户和运行标识共同定位，恢复时同时核对索引与原聚合。 */
    public Optional<ExpenseDraftAssistRun> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM agent_expense_draft_run WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 调用用例先锁报销再锁运行，锁不跨越模型网络请求。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String tenant, UUID id) {
        return !jdbc.queryForList("SELECT id FROM agent_expense_draft_run WHERE tenant_id=? AND id=? FOR UPDATE", String.class, tenant, id.toString()).isEmpty();
    }

    /** 扫描有界队列，运行中记录仅在原租约过期后进入结算候选。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id,trace_id FROM agent_expense_draft_run WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?)
                ORDER BY created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), Timestamp.from(now));
    }

    /** 列表只返回版本和状态，行程、目录、模型正文在本人详情入口读取。 */
    public Page page(String tenant, UUID reportId, int page, int size) {
        var items = jdbc.query("""
                SELECT id,application_version,financial_version,status,version,created_at FROM agent_expense_draft_run
                WHERE tenant_id=? AND report_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new Summary(UUID.fromString(row.getString("id")), row.getLong("application_version"),
                row.getLong("financial_version"), ExpenseDraftAssistRun.Status.valueOf(row.getString("status")),
                row.getLong("version"), row.getTimestamp("created_at").toInstant()), tenant, reportId.toString(), size, (long) page * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_expense_draft_run WHERE tenant_id=? AND report_id=?", Long.class, tenant, reportId.toString());
        return new Page(items, total, page, size);
    }

    private void append(ExpenseDraftAssistRun run) {
        jdbc.update("INSERT INTO agent_expense_draft_transition(tenant_id,run_id,run_version,status,state_json) VALUES(?,?,?,?,?)",
                run.context().tenantId(), run.context().id().toString(), run.state().version(), run.state().status().name(), json.write(run.state()));
    }

    private ExpenseDraftAssistRun restore(ResultSet row, int index) throws SQLException {
        var context = json.read(row.getString("context_json"), ExpenseDraftAssistRun.Context.class);
        var state = json.read(row.getString("state_json"), ExpenseDraftAssistRun.State.class); var input = context.input();
        var run = ExpenseDraftAssistRun.restore(context, state); var lease = row.getTimestamp("lease_until");
        var expectedLease = state.status() == ExpenseDraftAssistRun.Status.RUNNING ? state.leaseUntil() : null;
        if (!context.tenantId().equals(row.getString("tenant_id")) || !context.id().toString().equals(row.getString("id"))
                || !input.reportId().toString().equals(row.getString("report_id")) || !input.applicationId().toString().equals(row.getString("application_id"))
                || !context.requestedBy().equals(row.getString("requested_by")) || input.applicationVersion() != row.getLong("application_version")
                || input.financialVersion() != row.getLong("financial_version") || !state.status().name().equals(row.getString("status"))
                || state.version() != row.getLong("version") || !context.createdAt().equals(row.getTimestamp("created_at").toInstant())
                || !Objects.equals(lease == null ? null : lease.toInstant(), expectedLease)
                || !Objects.equals(run.active() ? input.reportId().toString() : null, row.getString("active_report_id"))) {
            throw new IllegalStateException("Persisted expense draft assist binding is inconsistent");
        }
        return run;
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense draft assistance changed before persistence"); }

    /**
     * 队列扫描不读取或暴露原模型内容。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) { }
    /**
     * 历史索引保留原双版本，不把确认状态解释为已保存费用。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, long applicationVersion, long financialVersion, ExpenseDraftAssistRun.Status status, long version, Instant createdAt) { }
    /**
     * 有界分页的参数约束由 HTTP 入口统一执行。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) { }
}
