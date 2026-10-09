package io.agentflow.budget;

import io.agentflow.budget.mapper.BudgetAdjustmentCheckRepositoryMapper;
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
import java.util.function.Function;

/**
 * 独立预检任务及其原始输入、预算台账依据；本地进程不持有唯一执行状态。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetAdjustmentCheckRepository {
    private final BudgetAdjustmentCheckRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final BudgetAdjustmentRepository requests;

    /** 与业务版本和幂等登记共用数据源。 */
    public JdbcBudgetAdjustmentCheckRepository(
            BudgetAdjustmentCheckRepositoryMapper sqlMapper,
            JsonUtil json,
            BudgetAdjustmentRepository requests) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.requests = requests;
    }

    /** 一个单据同时只允许一个活动预检，重复请求不会产生并行外发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetAdjustmentCheck job) {
        var input = job.input();
        if (job.status() != BudgetAdjustmentCheck.Status.QUEUED) throw conflict();
        var source =
                requests.find(input.tenantId(), input.requestId())
                        .orElseThrow(JdbcBudgetAdjustmentCheckRepository::conflict);
        if (!source.applicationId().equals(input.applicationId())
                || !source.employeeId().equals(input.employeeId())
                || source.version() != input.requestVersion()
                || !source.content().equals(input.content())
                || source.rounds().size() + 1 != input.roundNo()
                || source.approval() != null) throw conflict();
        try {
            sqlMapper.create(
                    DiagnosticContext.capture().traceId(),
                    input.tenantId(),
                    input.id().toString(),
                    input.requestId().toString(),
                    input.applicationId().toString(),
                    input.employeeId(),
                    input.applicationVersion(),
                    input.requestVersion(),
                    input.attempt(),
                    json.write(input),
                    json.write(job),
                    input.requestId().toString(),
                    Timestamp.from(job.createdAt()));
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "BUDGET_ADJUSTMENT_CHECK_ACTIVE",
                    "Budget adjustment already has an active precheck");
        }
        append(job);
    }

    /** 原输入不可替换，任务转换和审计追加必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetAdjustmentCheck job) {
        var input = job.input();
        var before =
                find(input.tenantId(), input.id())
                        .orElseThrow(JdbcBudgetAdjustmentCheckRepository::conflict);
        if (!before.input().equals(input) || !before.createdAt().equals(job.createdAt()))
            throw conflict();
        var expected =
                before.status() == BudgetAdjustmentCheck.Status.QUEUED
                        ? before.start(job.startedAt(), job.leaseUntil())
                        : before.finish(job.result(), job.completedAt());
        if (!expected.equals(job)) throw conflict();
        int updated =
                sqlMapper.update(
                        job.version(),
                        job.status().name(),
                        json.write(job),
                        job.active() ? input.requestId().toString() : null,
                        timestamp(job.leaseUntil()),
                        timestamp(job.completedAt()),
                        input.tenantId(),
                        input.id().toString(),
                        json.write(input),
                        job.version() - 1);
        if (updated != 1) throw conflict();
        append(job);
    }

    /** 租户列和输入/状态 JSON 的身份必须一致。 */
    public Optional<BudgetAdjustmentCheck> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 申请锁内分配递增序号，不用 UUID 或时间戳推断新旧检查结论。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextAttempt(String tenant, UUID requestId) {
        return latestAttempt(tenant, requestId) + 1;
    }

    /** 新尝试一旦登记，旧 READY 结果立即只供历史查看。 */
    public long latestAttempt(String tenant, UUID requestId) {
        return SqlRows.single(sqlMapper.latestAttempt(tenant, requestId.toString()));
    }

    /** 最新任务按业务检查序号定位；历史 UUID 游标不能用于判断新旧。 */
    public Optional<UUID> latestId(String tenant, UUID requestId) {
        return sqlMapper.latestId(tenant, requestId.toString()).stream()
                .findFirst()
                .map(UUID::fromString);
    }

    /** 排队前在单据锁下检查，唯一约束继续兜底并发。 */
    public boolean active(String tenant, UUID requestId) {
        return !sqlMapper.active(tenant, requestId.toString()).isEmpty();
    }

    /** 只读取十项；过期运行用于记录超时，不重复发送旧任务。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(Timestamp.from(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 历史只按单据归属查询，使用有界稳定游标。 */
    public List<BudgetAdjustmentCheck> list(String tenant, UUID requestId, UUID before, int limit) {

        return before == null
                ? SqlRows.map(
                        sqlMapper.listQuery(
                                before == null, new Object[] {tenant, requestId.toString(), limit}),
                        row())
                : SqlRows.map(
                        sqlMapper.listQuery2(
                                before == null,
                                new Object[] {
                                    tenant, requestId.toString(), before.toString(), limit
                                }),
                        row());
    }

    private Function<SqlRow, BudgetAdjustmentCheck> row() {
        return row -> {
            var job = json.read(row.getString("state_json"), BudgetAdjustmentCheck.class);
            var input = job.input();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"), BudgetAdjustmentCheck.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.requestId().toString().equals(row.getString("request_id"))
                    || !input.applicationId().toString().equals(row.getString("application_id"))
                    || !input.employeeId().equals(row.getString("employee_id"))
                    || input.applicationVersion() != row.getLong("application_version")
                    || input.attempt() != row.getLong("attempt_no")
                    || input.requestVersion() != row.getLong("request_version")
                    || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status"))
                    || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(
                            job.active() ? input.requestId().toString() : null,
                            row.getString("active_request_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(
                            job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException(
                        "Persisted budget adjustment check identity is inconsistent");
            }
            return job;
        };
    }

    private void append(BudgetAdjustmentCheck job) {
        sqlMapper.append(
                job.input().tenantId(),
                job.input().id().toString(),
                job.version(),
                json.write(job));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget adjustment check input or version changed"); }

    /**
     * 扫描项不复制敏感快照，领取后在锁内重新读取。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 缺少原业务事实时保持空值，不继承工作线程残留值。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
