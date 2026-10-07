package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 到期期限绑定真实退回轮次，所有状态变化追加历史，后台扫描不跳过后续单据。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseBudgetRetentionRepository {
    public static final int BATCH_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 期限登记与轮次结论共用事务，释放登记与预算 outbox 共用事务。 */
    public JdbcExpenseBudgetRetentionRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 不接受不存在、尚在审批或绑定其他报销的轮次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseBudgetRetention value) {
        if (value.version() != 1 || value.status() != ExpenseBudgetRetention.Status.RETAINED
                || !value.updatedAt().equals(value.retainedAt())) throw conflict();
        int inserted = jdbc.update("""
                INSERT INTO expense_budget_retention(tenant_id,report_id,application_id,round_no,stopped_status,
                    retained_at,retention_days,expires_at,status,version,state_json,updated_at,trace_id)
                SELECT r.tenant_id,r.id,r.application_id,s.round_no,s.status,s.completed_at,?,?,'RETAINED',1,?,?,?
                FROM expense_report r JOIN approval_submission_round s
                    ON s.tenant_id=r.tenant_id AND s.application_id=r.application_id
                WHERE r.tenant_id=? AND r.id=? AND r.application_id=? AND s.round_no=? AND s.status=? AND s.completed_at=?
                """, value.policy().retentionDays(), Timestamp.from(value.expiresAt()), json.write(value), Timestamp.from(value.updatedAt()), DiagnosticContext.capture().traceId(),
                value.tenantId(), value.reportId().toString(), value.applicationId().toString(), value.roundNo(),
                value.stoppedStatus().name(), Timestamp.from(value.retainedAt()));
        if (inserted != 1) throw conflict();
        append(value);
    }

    /** 原期限、归属和停止原因不可替换；乐观版本与审计一并提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseBudgetRetention value) {
        int updated = jdbc.update("""
                UPDATE expense_budget_retention SET status=?,release_operation_id=?,version=?,state_json=?,updated_at=?
                WHERE tenant_id=? AND report_id=? AND round_no=? AND application_id=? AND stopped_status=?
                    AND retained_at=? AND retention_days=? AND expires_at=? AND version=?
                """, value.status().name(), text(value.releaseOperationId()), value.version(), json.write(value), Timestamp.from(value.updatedAt()),
                value.tenantId(), value.reportId().toString(), value.roundNo(), value.applicationId().toString(), value.stoppedStatus().name(),
                Timestamp.from(value.retainedAt()), value.policy().retentionDays(), Timestamp.from(value.expiresAt()), value.version() - 1);
        if (updated != 1) throw conflict();
        append(value);
    }

    /** 当前轮次和历史轮次按各自固定期限读取，不用当前配置重算。 */
    public Optional<ExpenseBudgetRetention> find(String tenant, UUID reportId, int roundNo) {
        return jdbc.query("SELECT * FROM expense_budget_retention WHERE tenant_id=? AND report_id=? AND round_no=?",
                this::map, tenant, reportId.toString(), roundNo).stream().findFirst();
    }

    /** 按稳定复合键翻页；未知任务留在原处重试，但不会永久占据第一页。 */
    public List<Candidate> candidates(Instant now, Candidate after) {
        String condition = after == null ? "" : " AND (r.tenant_id>? OR (r.tenant_id=? AND r.report_id>?) OR (r.tenant_id=? AND r.report_id=? AND r.round_no>?))";
        Object[] parameters = after == null ? new Object[]{Timestamp.from(now), BATCH_SIZE}
                : new Object[]{Timestamp.from(now), after.tenantId(), after.tenantId(), after.reportId().toString(),
                    after.tenantId(), after.reportId().toString(), after.roundNo(), BATCH_SIZE};
        return jdbc.query("""
                SELECT r.tenant_id,r.report_id,r.round_no,r.trace_id,business.business_no,submitted.process_instance_id FROM expense_budget_retention r
                LEFT JOIN approval_application business ON business.tenant_id=r.tenant_id AND business.id=r.application_id
                LEFT JOIN approval_submission_round submitted ON submitted.tenant_id=business.tenant_id AND submitted.application_id=business.id AND submitted.round_no=r.round_no
                WHERE r.status IN ('RETAINED','RECONCILING','RELEASE_QUEUED') AND r.expires_at<=?
                """ + condition + " ORDER BY r.tenant_id,r.report_id,r.round_no LIMIT ?",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("report_id")), row.getInt("round_no"), row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), parameters);
    }

    private ExpenseBudgetRetention map(ResultSet row, int index) throws SQLException {
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
        jdbc.update("INSERT INTO expense_budget_retention_revision(tenant_id,report_id,round_no,version,state_json) VALUES(?,?,?,?,?)",
                value.tenantId(), value.reportId().toString(), value.roundNo(), value.version(), json.write(value));
    }
    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget retention source or version changed"); }

    /**
     * 调度游标只携带定位键，执行时在单据锁内重新读取当前状态。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID reportId, int roundNo, String traceId, String businessNo, String processInstanceId) {
        /** 旧扫描不推测业务关联，也不借用调用线程的实例或任务。 */
        public Candidate(String tenantId, UUID reportId, int roundNo, String traceId) { this(tenantId, reportId, roundNo, traceId, null, null); }
        /** 旧来源保持空值，由入口生成稳定的独立执行标识。 */
        public Candidate(String tenantId, UUID reportId, int roundNo) { this(tenantId, reportId, roundNo, null); }
    }
}
