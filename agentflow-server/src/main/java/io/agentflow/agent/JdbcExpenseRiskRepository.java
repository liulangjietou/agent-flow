package io.agentflow.agent;

import io.agentflow.agent.mapper.ExpenseRiskRepositoryMapper;
import io.agentflow.auth.DeferredActorAuthentication;
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
 * 风险运行、原来源索引和转换轨迹共用事务；认证引用只供内部重新认证，不作为页面凭据。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseRiskRepository {
    private static final int BATCH_SIZE = 10;
    private final ExpenseRiskRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共用业务事务，不能在写入一半时把运行交给后台线程。 */
    public JdbcExpenseRiskRepository(ExpenseRiskRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 调用方先锁定并授权全部来源，SQL 再核对所有选中申请与费用版本，任一变化整体回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseRiskRun run, DeferredActorAuthentication.LoginReference login) {
        var context = run.context();
        var primary = context.input().documents().get(0);
        if (run.state().status() != ExpenseRiskRun.Status.QUEUED || run.state().version() != 1)
            throw conflict();
        if (!sourceDigestsMatch(context.input()))
            throw new DomainException(
                    "INVALID_AGENT_INPUT", "Risk source content digest does not match");
        Objects.requireNonNull(login);
        try {
            int count =
                    sqlMapper.create(
                            context.id().toString(),
                            context.requestedBy(),
                            context.taskId(),
                            login.kind().name(),
                            login.value(),
                            json.write(context),
                            json.write(run.state()),
                            Timestamp.from(context.createdAt()),
                            DiagnosticContext.capture().traceId(),
                            context.tenantId(),
                            primary.reportId().toString(),
                            primary.applicationId().toString(),
                            primary.applicationVersion(),
                            primary.financialVersion(),
                            primary.roundNo());
            if (count != 1) throw changed();
        } catch (DuplicateKeyException active) {
            throw new DomainException(
                    "AGENT_RUN_ACTIVE", "Expense report already has an active risk explanation");
        }
        for (var document : context.input().documents()) {
            int count =
                    sqlMapper.create2(
                            context.id().toString(),
                            document.ordinal(),
                            document.roundNo(),
                            document.snapshotDigest(),
                            context.tenantId(),
                            document.reportId().toString(),
                            document.applicationId().toString(),
                            document.applicationVersion(),
                            document.financialVersion());
            if (count != 1) throw changed();
        }
        append(run);
    }

    /** 转换与审计原子更新；不可变上下文不能随状态迁移替换，认证引用没有修改入口。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseRiskRun run, long expectedVersion) {
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
        int count =
                sqlMapper.update(
                        state.status().name(),
                        state.version(),
                        json.write(state),
                        run.active()
                                ? context.input().documents().get(0).reportId().toString()
                                : null,
                        state.status() == ExpenseRiskRun.Status.RUNNING
                                ? Timestamp.from(state.leaseUntil())
                                : null,
                        context.tenantId(),
                        context.id().toString(),
                        expectedVersion,
                        previous,
                        json.write(context));
        if (count != 1) throw conflict();
        append(run);
    }

    /** 恢复时验证来源索引、内容摘要与状态迁移，损坏数据不能成为新的可发送输入。 */
    public Optional<Entry> find(String tenant, UUID id) {
        var found =
                SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                        .findFirst();
        found.ifPresent(entry -> requireDocuments(entry.run()));
        return found;
    }

    /** 业务服务先按统一顺序锁来源，再锁运行；HTTP 发送不能持有此锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String tenant, UUID id) {
        return !sqlMapper.lock(tenant, id.toString()).isEmpty();
    }

    /** 扫描队列或已到期租约；运行中未到期的请求不会被另一个进程重领。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(Timestamp.from(now), BATCH_SIZE),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id"),
                                row.getString("task_id")));
    }

    /** 索引限于已授权的主单原轮次，不返回对照单标识、认证引用或模型正文。 */
    public Page page(String tenant, UUID reportId, int roundNo, int page, int size) {
        var items =
                SqlRows.map(
                        sqlMapper.page(
                                tenant, reportId.toString(), roundNo, size, (long) page * size),
                        row ->
                                new Summary(
                                        UUID.fromString(row.getString("id")),
                                        ExpenseRiskRun.Status.valueOf(row.getString("status")),
                                        row.getLong("version"),
                                        row.getTimestamp("created_at").toInstant()));
        long total = SqlRows.single(sqlMapper.page2(tenant, reportId.toString(), roundNo));
        return new Page(items, total, page, size);
    }

    private Entry restore(SqlRow row) {
        var context = json.read(row.getString("context_json"), ExpenseRiskRun.Context.class);
        var state = json.read(row.getString("state_json"), ExpenseRiskRun.State.class); var primary = context.input().documents().get(0);
        var run = ExpenseRiskRun.restore(context, state); var lease = row.getTimestamp("lease_until");
        if (!context.tenantId().equals(row.getString("tenant_id")) || !context.id().toString().equals(row.getString("id"))
                || !primary.reportId().toString().equals(row.getString("report_id")) || !primary.applicationId().toString().equals(row.getString("application_id"))
                || primary.roundNo() != row.getInt("round_no") || !context.requestedBy().equals(row.getString("requested_by"))
                || !context.taskId().equals(row.getString("task_id")) || !state.status().name().equals(row.getString("status"))
                || state.version() != row.getLong("version") || !context.createdAt().equals(row.getTimestamp("created_at").toInstant())
                || !Objects.equals(lease == null ? null : lease.toInstant(), state.status() == ExpenseRiskRun.Status.RUNNING ? state.leaseUntil() : null)
                || !Objects.equals(run.active() ? primary.reportId().toString() : null, row.getString("active_report_id"))
                || !sourceDigestsMatch(context.input())) throw inconsistent();
        var login = new DeferredActorAuthentication.LoginReference(DeferredActorAuthentication.Kind.valueOf(row.getString("authentication_kind")), row.getString("login_reference"));
        return new Entry(run, login);
    }

    private void requireDocuments(ExpenseRiskRun run) {
        var documents =
                SqlRows.map(
                        sqlMapper.requireDocuments(
                                run.context().tenantId(), run.context().id().toString()),
                        (row, ordinal) ->
                                new DocumentBinding(
                                        row.getInt("ordinal"),
                                        row.getString("report_id"),
                                        row.getString("application_id"),
                                        row.getLong("application_version"),
                                        row.getLong("financial_version"),
                                        row.getInt("round_no"),
                                        row.getString("snapshot_digest")));
        var expected =
                run.context().input().documents().stream()
                        .map(
                                document ->
                                        new DocumentBinding(
                                                document.ordinal(),
                                                document.reportId().toString(),
                                                document.applicationId().toString(),
                                                document.applicationVersion(),
                                                document.financialVersion(),
                                                document.roundNo(),
                                                document.snapshotDigest()))
                        .toList();
        if (!documents.equals(expected)) throw inconsistent();
    }

    private boolean sourceDigestsMatch(ExpenseRiskInput input) {
        return input.sources().stream().allMatch(source -> source.reference().contentDigest().equals(AssistConfiguration.digest(source.content())));
    }

    private void append(ExpenseRiskRun run) {
        sqlMapper.append(
                run.context().tenantId(),
                run.context().id().toString(),
                run.state().version(),
                run.state().status().name(),
                json.write(run.state()));
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense risk run changed before persistence"); }

    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Expense risk source versions changed before persistence"); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted expense risk binding is inconsistent"); }

    /**
     * 内部执行记录带原登录引用；公开接口必须显式投影，不能返回该对象。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Entry(ExpenseRiskRun run, DeferredActorAuthentication.LoginReference login) {}

    /**
     * 后台候选只携带定位，不携带身份凭据或业务材料。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID id,
            String traceId,
            String businessNo,
            String processInstanceId,
            String taskId) {
        /** 没有业务关联的历史调用保持缺失事实，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null, null); }
    }

    /**
     * 主单已授权轮次的轻量历史，不泄露对照单信息。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, ExpenseRiskRun.Status status, long version, Instant createdAt) {}

    /**
     * 页面参数在入口校验，仓储只使用参数化查询。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) {}

    /**
     * 来源索引与完整上下文相互校验，不能静默修复被修改过的绑定。
     *
     * @author owlzhangfq@gmail.com
     */
    private record DocumentBinding(
            int ordinal,
            String reportId,
            String applicationId,
            long applicationVersion,
            long financialVersion,
            int roundNo,
            String snapshotDigest) {}
}
