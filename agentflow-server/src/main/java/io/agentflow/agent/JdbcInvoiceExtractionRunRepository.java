package io.agentflow.agent;

import io.agentflow.agent.mapper.InvoiceExtractionRunRepositoryMapper;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 抽取任务、活动唯一键、租约和追加轨迹同事务保存，不修改发票财务聚合。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcInvoiceExtractionRunRepository {
    private static final int BATCH_SIZE = 10;
    private static final String AUDIT_QUEUE = "INVOICE_EXTRACTION_QUEUE";
    private static final String AUDIT_CONFIRM = "INVOICE_EXTRACTION_CONFIRM";
    private static final String AUDIT_DISMISS = "INVOICE_EXTRACTION_DISMISS";
    private final InvoiceExtractionRunRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 冻结上下文只含原件引用，文件字节和模型凭据不进入运行记录。 */
    public JdbcInvoiceExtractionRunRepository(
            InvoiceExtractionRunRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 登记时复核原件归属、指纹和 READY 状态；唯一键冲突交由外层事务回滚。 */
    @Transactional
    public void create(InvoiceExtractionRun run) {
        var context = run.context();
        var input = context.input();
        if (run.state().status() != InvoiceExtractionRun.Status.QUEUED
                || run.state().version() != 1) throw conflict();
        try {
            int changed =
                    sqlMapper.create(
                            context.id().toString(),
                            input.pageCount(),
                            context.method().name(),
                            context.targetDigest(),
                            json.write(context),
                            json.write(run.state()),
                            timestamp(context.createdAt()),
                            DiagnosticContext.capture().traceId(),
                            context.tenantId(),
                            input.invoiceId().toString(),
                            context.requestedBy(),
                            input.originalId().toString(),
                            input.originalDigest(),
                            input.format().name(),
                            input.originalBytes());
            if (changed != 1)
                throw new DomainException(
                        "AGENT_INPUT_CHANGED", "Invoice original changed before queue persistence");
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException("AGENT_RUN_ACTIVE", "Invoice extraction is already active");
        }
        append(run);
    }

    /** 租约与阶段同一语句更新，不能留下没有租约的 RUNNING 或带活动键的终态。 */
    @Transactional
    public void update(InvoiceExtractionRun run, long expectedVersion, Instant leaseUntil) {
        var context = run.context();
        var state = run.state();
        if (state.version() != expectedVersion + 1) throw conflict();
        String previous =
                switch (state.status()) {
                    case RUNNING -> "QUEUED";
                    case COMPLETED, FAILED -> "RUNNING";
                    case CONFIRMED, DISMISSED -> "COMPLETED";
                    case QUEUED -> throw conflict();
                };
        int changed =
                sqlMapper.update(
                        state.status().name(),
                        state.version(),
                        json.write(state),
                        timestamp(leaseUntil),
                        run.active() ? context.input().invoiceId().toString() : null,
                        context.tenantId(),
                        context.id().toString(),
                        expectedVersion,
                        previous,
                        json.write(context));
        if (changed != 1) throw conflict();
        append(run);
    }

    /** 按租户定位，并校验索引列与领域上下文一致。 */
    public Optional<InvoiceExtractionRun> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                .findFirst();
    }

    /** 业务层锁顺序为组织目录、原件、运行；锁只能在事务中使用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String tenant, UUID id) {
        return !sqlMapper.lock(tenant, id.toString()).isEmpty();
    }

    /** 一张原件只允许一个排队或运行中的抽取；已结束建议保留历史。 */
    public Optional<UUID> activeId(String tenant, UUID invoiceId) {
        return sqlMapper.activeId(tenant, invoiceId.toString()).stream()
                .findFirst()
                .map(UUID::fromString);
    }

    /** 超时任务与待执行任务有界扫描，超时仅结算，不重新发送。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), BATCH_SIZE),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id")));
    }

    /** 领取或结算时在运行锁内读取原租约，过期结果不可覆盖终态。 */
    public Instant lease(String tenant, UUID id) {
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.lease(tenant, id.toString()),
                        row -> {
                            var value = row.getTimestamp(1);
                            return value == null ? null : value.toInstant();
                        }));
    }

    /** 分页索引不读取票面值、摘录或人工修订，归属授权由应用入口完成。 */
    public Page page(String tenant, UUID invoiceId, int page, int size) {
        var rows =
                SqlRows.map(
                        sqlMapper.page(tenant, invoiceId.toString(), size, (long) page * size),
                        row ->
                                new Summary(
                                        UUID.fromString(row.getString("id")),
                                        InvoiceExtractionSuggestion.Method.valueOf(
                                                row.getString("method")),
                                        InvoiceExtractionRun.Status.valueOf(
                                                row.getString("status")),
                                        row.getLong("version"),
                                        row.getTimestamp("created_at").toInstant()));
        long total = SqlRows.single(sqlMapper.page2(tenant, invoiceId.toString()));
        return new Page(rows, total, page, size);
    }

    private void append(InvoiceExtractionRun run) {
        sqlMapper.append(
                run.context().tenantId(),
                run.context().id().toString(),
                run.state().version(),
                run.state().status().name(),
                json.write(run.state()));
        String action =
                switch (run.state().status()) {
                    case QUEUED -> AUDIT_QUEUE;
                    case CONFIRMED -> AUDIT_CONFIRM;
                    case DISMISSED -> AUDIT_DISMISS;
                    default -> null;
                };
        if (action == null) return;
        // 统一审计只记录本人的明确操作；自动执行有独立轨迹，不能冒充本人操作或复制票面内容。
        var context = run.context();
        sqlMapper.append2(
                UUID.randomUUID().toString(),
                context.tenantId(),
                UUID.randomUUID().toString(),
                context.id().toString(),
                run.state().version(),
                action,
                context.requestedBy(),
                json.write(
                        Map.of(
                                "invoiceId",
                                context.input().invoiceId(),
                                "method",
                                context.method(),
                                "status",
                                run.state().status())),
                timestamp(
                        run.state().review() == null
                                ? context.createdAt()
                                : run.state().review().at()));
    }

    private InvoiceExtractionRun restore(SqlRow row) {
        var context = json.read(row.getString("context_json"), InvoiceExtractionRun.Context.class);
        var state = json.read(row.getString("state_json"), InvoiceExtractionRun.State.class);
        var input = context.input();
        var run = InvoiceExtractionRun.restore(context, state);
        if (!context.id().toString().equals(row.getString("id")) || !context.tenantId().equals(row.getString("tenant_id"))
                || !context.requestedBy().equals(row.getString("owner_id")) || !input.invoiceId().toString().equals(row.getString("invoice_id"))
                || !input.originalId().toString().equals(row.getString("original_id")) || !input.originalDigest().equals(row.getString("original_digest"))
                || !input.format().name().equals(row.getString("original_format")) || input.originalBytes() != row.getLong("original_bytes")
                || input.pageCount() != row.getInt("page_count") || !context.method().name().equals(row.getString("method"))
                || !Objects.equals(context.targetDigest(), row.getString("target_digest"))
                || !context.createdAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("created_at").toInstant())
                || !state.status().name().equals(row.getString("status")) || state.version() != row.getLong("version")
                || !Objects.equals(run.active() ? input.invoiceId().toString() : null, row.getString("active_invoice_id"))) {
            throw new IllegalStateException("Persisted invoice extraction context is inconsistent");
        }
        return run;
    }

    // JDBC 索引时间统一截断到微秒，JSON 保留领域原时间，避免数据库舍入产生不一致。
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Invoice extraction changed before persistence"); }

    /**
     * 扫描结果仅携带不透明定位标识。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) {}

    /**
     * 历史索引不包含票面数据。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Summary(
            UUID id,
            InvoiceExtractionSuggestion.Method method,
            InvoiceExtractionRun.Status status,
            long version,
            Instant createdAt) {}

    /**
     * 本人历史的有界分页。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<Summary> items, long total, int page, int pageSize) {}
}
