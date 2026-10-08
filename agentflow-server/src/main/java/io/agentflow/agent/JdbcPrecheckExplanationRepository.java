package io.agentflow.agent;

import io.agentflow.agent.mapper.PrecheckExplanationRepositoryMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 解释运行与迁移轨迹持久化；单据、本人和原预检同时绑定，活动唯一键限制并发。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPrecheckExplanationRepository {
    private final PrecheckExplanationRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 原始输入不进入索引或日志，队列与人工复核共用业务事务。 */
    public JdbcPrecheckExplanationRepository(
            PrecheckExplanationRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 在同一事务核对原预检和双版本后插入，不能借用其他单据的检查。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PrecheckExplanationRun run) {
        var context = run.context();
        var input = context.input();
        if (run.state().status() != PrecheckExplanationRun.Status.QUEUED) throw conflict();
        try {
            int count =
                    sqlMapper.create(
                            context.id().toString(),
                            json.write(context),
                            json.write(run.state()),
                            Timestamp.from(context.createdAt()),
                            DiagnosticContext.capture().traceId(),
                            context.tenantId(),
                            input.reportId().toString(),
                            input.applicationId().toString(),
                            context.requestedBy(),
                            input.applicationVersion(),
                            input.financialVersion(),
                            input.precheckId().toString(),
                            input.attempt(),
                            input.result().name());
            if (count != 1)
                throw new DomainException(
                        "AGENT_INPUT_CHANGED", "Precheck changed before explanation persistence");
        } catch (DuplicateKeyException active) {
            throw new DomainException(
                    "AGENT_RUN_ACTIVE", "Expense report already has an active explanation");
        }
        append(run);
    }

    /** 状态、租约与追加轨迹同事务保存，失败不会留下半份复核。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PrecheckExplanationRun run, long expectedVersion) {
        var context = run.context();
        var state = run.state();
        if (state.version() != expectedVersion + 1) throw conflict();
        String previous =
                switch (state.status()) {
                    case RUNNING -> "QUEUED";
                    case COMPLETED, FAILED -> "RUNNING";
                    case ADOPTED, DISMISSED -> "COMPLETED";
                    case QUEUED -> throw conflict();
                };
        int changed =
                sqlMapper.update(
                        state.status().name(),
                        state.version(),
                        json.write(state),
                        run.active() ? context.input().reportId().toString() : null,
                        state.status() == PrecheckExplanationRun.Status.RUNNING
                                ? Timestamp.from(state.leaseUntil())
                                : null,
                        context.tenantId(),
                        context.id().toString(),
                        expectedVersion,
                        previous,
                        json.write(context));
        if (changed != 1) throw conflict();
        append(run);
    }

    /** 标识总与租户共同定位，索引列必须与不可变上下文一致。 */
    public Optional<PrecheckExplanationRun> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                .findFirst();
    }

    /** 先锁单据再锁运行，不在模型 HTTP 期间持有锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String tenant, UUID id) {
        return !sqlMapper.lock(tenant, id.toString()).isEmpty();
    }

    /** 每次最多十项，运行中仅在租约过期后再次扫描。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(Timestamp.from(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no")));
    }

    /** 页面历史只读索引，原始内容仅在单条详情中返回本人。 */
    public Page page(String tenant, UUID reportId, int page, int size) {
        var items =
                SqlRows.map(
                        sqlMapper.page(tenant, reportId.toString(), size, (long) page * size),
                        row ->
                                new Summary(
                                        UUID.fromString(row.getString("id")),
                                        UUID.fromString(row.getString("precheck_id")),
                                        row.getLong("precheck_attempt"),
                                        row.getLong("application_version"),
                                        row.getLong("financial_version"),
                                        PrecheckExplanationRun.Status.valueOf(
                                                row.getString("status")),
                                        row.getLong("version"),
                                        row.getTimestamp("created_at").toInstant()));
        long total = SqlRows.single(sqlMapper.page2(tenant, reportId.toString()));
        return new Page(items, total, page, size);
    }

    private void append(PrecheckExplanationRun run) {
        sqlMapper.append(
                run.context().tenantId(),
                run.context().id().toString(),
                run.state().version(),
                run.state().status().name(),
                json.write(run.state()));
    }

    private PrecheckExplanationRun restore(SqlRow row) {
        var context = json.read(row.getString("context_json"), PrecheckExplanationRun.Context.class);
        var state = json.read(row.getString("state_json"), PrecheckExplanationRun.State.class); var input = context.input();
        var lease = row.getTimestamp("lease_until"); var expectedLease = state.status() == PrecheckExplanationRun.Status.RUNNING ? state.leaseUntil() : null;
        var run = PrecheckExplanationRun.restore(context, state);
        if (!context.tenantId().equals(row.getString("tenant_id")) || !context.id().toString().equals(row.getString("id"))
                || !input.reportId().toString().equals(row.getString("report_id")) || !input.applicationId().toString().equals(row.getString("application_id"))
                || !context.requestedBy().equals(row.getString("requested_by")) || input.applicationVersion() != row.getLong("application_version")
                || input.financialVersion() != row.getLong("financial_version") || !input.precheckId().toString().equals(row.getString("precheck_id"))
                || input.attempt() != row.getLong("precheck_attempt") || !state.status().name().equals(row.getString("status"))
                || state.version() != row.getLong("version") || !context.createdAt().equals(row.getTimestamp("created_at").toInstant())
                || !Objects.equals(lease == null ? null : lease.toInstant(), expectedLease)
                || !Objects.equals(run.active() ? input.reportId().toString() : null, row.getString("active_report_id"))) {
            throw new IllegalStateException("Persisted precheck explanation binding is inconsistent");
        }
        return run;
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Precheck explanation changed before persistence"); }

    /**
     * 队列扫描仅提供定位键。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo) {
        /** 没有业务关联的历史调用保持缺失事实，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null); }
    }

    /**
     * 轻量索引不携带模型文本与费用内容。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Summary(
            UUID id,
            UUID precheckId,
            long attempt,
            long applicationVersion,
            long financialVersion,
            PrecheckExplanationRun.Status status,
            long version,
            Instant createdAt) {}

    /**
     * 分页由入口限制，稳定顺序保留全部历史运行。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) {}
}
