package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePrecheckRepositoryMapper;
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
 * 独立预检任务及其原始输入、转换证据；本地进程不持有唯一执行状态。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePrecheckRepository {
    private final ExpensePrecheckRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 与业务版本和幂等登记共用数据源。 */
    public JdbcExpensePrecheckRepository(ExpensePrecheckRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 一个单据同时只允许一个活动预检，重复请求不会产生并行外发。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePrecheckJob job) {
        var input = job.input();
        if (job.status() != ExpensePrecheckJob.Status.QUEUED) throw conflict();
        try {
            sqlMapper.create(
                    input.tenantId(),
                    input.id().toString(),
                    input.reportId().toString(),
                    input.applicationId().toString(),
                    input.employeeId(),
                    input.applicationVersion(),
                    input.financialVersion(),
                    input.attempt(),
                    json.write(input),
                    json.write(job),
                    input.reportId().toString(),
                    Timestamp.from(job.createdAt()),
                    DiagnosticContext.capture().traceId());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "EXPENSE_PRECHECK_ACTIVE", "Expense report already has an active precheck");
        }
        append(job);
    }

    /** 原输入不可替换，任务转换和审计追加必须一起成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePrecheckJob job) {
        var input = job.input();
        int updated =
                sqlMapper.update(
                        job.version(),
                        job.status().name(),
                        json.write(job),
                        job.active() ? input.reportId().toString() : null,
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
    public Optional<ExpensePrecheckJob> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 申请锁内分配递增序号，不用 UUID 或时间戳推断新旧检查结论。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextAttempt(String tenant, UUID reportId) {
        return latestAttempt(tenant, reportId) + 1;
    }

    /** 新尝试一旦登记，旧 READY 结果立即只供历史查看。 */
    public long latestAttempt(String tenant, UUID reportId) {
        return SqlRows.single(sqlMapper.latestAttempt(tenant, reportId.toString()));
    }

    /** 最新任务按业务检查序号定位；历史 UUID 游标不能用于判断新旧。 */
    public Optional<UUID> latestId(String tenant, UUID reportId) {
        return sqlMapper.latestId(tenant, reportId.toString()).stream()
                .findFirst()
                .map(UUID::fromString);
    }

    /** 排队前在单据锁下检查，唯一约束继续兜底并发。 */
    public boolean active(String tenant, UUID reportId) {
        return !sqlMapper.active(tenant, reportId.toString()).isEmpty();
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
                                row.getString("business_no")));
    }

    /** 历史只按单据归属查询，使用有界稳定游标。 */
    public List<ExpensePrecheckJob> list(String tenant, UUID reportId, UUID before, int limit) {

        return before == null
                ? SqlRows.map(
                        sqlMapper.listQuery(
                                before == null, new Object[] {tenant, reportId.toString(), limit}),
                        row())
                : SqlRows.map(
                        sqlMapper.listQuery2(
                                before == null,
                                new Object[] {
                                    tenant, reportId.toString(), before.toString(), limit
                                }),
                        row());
    }

    private Function<SqlRow, ExpensePrecheckJob> row() {
        return row -> {
            var job = json.read(row.getString("state_json"), ExpensePrecheckJob.class);
            var input = job.input();
            if (!input.equals(
                            json.read(row.getString("input_json"), ExpensePrecheckJob.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !input.reportId().toString().equals(row.getString("report_id"))
                    || !input.applicationId().toString().equals(row.getString("application_id"))
                    || !input.employeeId().equals(row.getString("employee_id"))
                    || input.applicationVersion() != row.getLong("application_version")
                    || input.attempt() != row.getLong("attempt_no")
                    || input.financialVersion() != row.getLong("financial_version")
                    || job.version() != row.getLong("version")
                    || !job.status().name().equals(row.getString("status"))
                    || !job.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !Objects.equals(
                            job.active() ? input.reportId().toString() : null,
                            row.getString("active_report_id"))
                    || !Objects.equals(job.leaseUntil(), instant(row.getTimestamp("lease_until")))
                    || !Objects.equals(
                            job.completedAt(), instant(row.getTimestamp("completed_at")))) {
                throw new IllegalStateException(
                        "Persisted expense precheck identity is inconsistent");
            }
            return job;
        };
    }

    private void append(ExpensePrecheckJob job) {
        sqlMapper.append(
                job.input().tenantId(),
                job.input().id().toString(),
                job.version(),
                json.write(job));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense precheck input or version changed"); }

    /**
     * 扫描项不复制敏感快照，领取后在锁内重新读取。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo) {
        /** 没有原业务事实的历史调用保留空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null); }
    }
}
