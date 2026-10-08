package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseBudgetRetentionRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

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
 * 到期期限绑定真实退回轮次，所有状态变化追加历史，后台扫描不跳过后续单据。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseBudgetRetentionRepository {
    public static final int BATCH_SIZE = 50;
    private final ExpenseBudgetRetentionRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 期限登记与轮次结论共用事务，释放登记与预算 outbox 共用事务。 */
    public JdbcExpenseBudgetRetentionRepository(
            ExpenseBudgetRetentionRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 不接受不存在、尚在审批或绑定其他报销的轮次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseBudgetRetention value) {
        if (value.version() != 1
                || value.status() != ExpenseBudgetRetention.Status.RETAINED
                || !value.updatedAt().equals(value.retainedAt())) throw conflict();
        int inserted =
                sqlMapper.create(
                        value.policy().retentionDays(),
                        Timestamp.from(value.expiresAt()),
                        json.write(value),
                        Timestamp.from(value.updatedAt()),
                        DiagnosticContext.capture().traceId(),
                        value.tenantId(),
                        value.reportId().toString(),
                        value.applicationId().toString(),
                        value.roundNo(),
                        value.stoppedStatus().name(),
                        Timestamp.from(value.retainedAt()));
        if (inserted != 1) throw conflict();
        append(value);
    }

    /** 原期限、归属和停止原因不可替换；乐观版本与审计一并提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseBudgetRetention value) {
        int updated =
                sqlMapper.update(
                        value.status().name(),
                        text(value.releaseOperationId()),
                        value.version(),
                        json.write(value),
                        Timestamp.from(value.updatedAt()),
                        value.tenantId(),
                        value.reportId().toString(),
                        value.roundNo(),
                        value.applicationId().toString(),
                        value.stoppedStatus().name(),
                        Timestamp.from(value.retainedAt()),
                        value.policy().retentionDays(),
                        Timestamp.from(value.expiresAt()),
                        value.version() - 1);
        if (updated != 1) throw conflict();
        append(value);
    }

    /** 当前轮次和历史轮次按各自固定期限读取，不用当前配置重算。 */
    public Optional<ExpenseBudgetRetention> find(String tenant, UUID reportId, int roundNo) {
        return SqlRows.map(sqlMapper.find(tenant, reportId.toString(), roundNo), this::map).stream()
                .findFirst();
    }

    /** 按稳定复合键翻页；未知任务留在原处重试，但不会永久占据第一页。 */
    public List<Candidate> candidates(Instant now, Candidate after) {

        Object[] parameters =
                after == null
                        ? new Object[] {Timestamp.from(now), BATCH_SIZE}
                        : new Object[] {
                            Timestamp.from(now),
                            after.tenantId(),
                            after.tenantId(),
                            after.reportId().toString(),
                            after.tenantId(),
                            after.reportId().toString(),
                            after.roundNo(),
                            BATCH_SIZE
                        };
        return SqlRows.map(
                sqlMapper.candidatesQuery(after == null, parameters),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("report_id")),
                                row.getInt("round_no"),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private ExpenseBudgetRetention map(SqlRow row) {
        var value = json.read(row.getString("state_json"), ExpenseBudgetRetention.class);
        if (!value.tenantId().equals(row.getString("tenant_id")) || !value.reportId().toString().equals(row.getString("report_id"))
                || !value.applicationId().toString().equals(row.getString("application_id")) || value.roundNo() != row.getInt("round_no")
                || !value.stoppedStatus().name().equals(row.getString("stopped_status")) || value.policy().retentionDays() != row.getInt("retention_days")
                || !value.retainedAt().equals(row.getTimestamp("retained_at").toInstant()) || !value.expiresAt().equals(row.getTimestamp("expires_at").toInstant())
                || !value.status().name().equals(row.getString("status")) || !Objects.equals(text(value.releaseOperationId()), row.getString("release_operation_id"))
                || value.version() != row.getLong("version") || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())) {
            throw new IllegalStateException("Persisted budget retention is inconsistent");
        }
        return value;
    }

    private void append(ExpenseBudgetRetention value) {
        sqlMapper.append(
                value.tenantId(),
                value.reportId().toString(),
                value.roundNo(),
                value.version(),
                json.write(value));
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget retention source or version changed"); }

    /**
     * 调度游标只携带定位键，执行时在单据锁内重新读取当前状态。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID reportId,
            int roundNo,
            String traceId,
            String businessNo,
            String processInstanceId) {
        /** 旧扫描不推测业务关联，也不借用调用线程的实例或任务。 */
        public Candidate(String tenantId, UUID reportId, int roundNo, String traceId) { this(tenantId, reportId, roundNo, traceId, null, null); }

        /** 旧来源保持空值，由入口生成稳定的独立执行标识。 */
        public Candidate(String tenantId, UUID reportId, int roundNo) { this(tenantId, reportId, roundNo, null); }
    }
}
