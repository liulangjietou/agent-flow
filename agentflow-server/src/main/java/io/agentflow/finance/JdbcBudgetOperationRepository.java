package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
 * 预算 outbox 及不可变命令审计，活动唯一约束跨进程阻止同单并行预算变更。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetOperationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 状态与审计共用同一个事务，失败不会留下半条恢复链。 */
    public JdbcBudgetOperationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 创建时要求已有单据财务版本和预算台账。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetOperation operation) {
        var command = operation.input().command();
        if (operation.version() != 1 || operation.status() != BudgetOperation.Status.QUEUED || operation.attempts() != 0) throw conflict();
        jdbc.update("""
                INSERT INTO budget_operation(tenant_id,id,report_id,financial_version,input_json,command_digest,state_json,
                version,status,attempts,active_report_id,created_at,updated_at,next_attempt_at)
                VALUES(?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?,?)
                """, command.tenantId(), command.id().toString(), command.position().reportId().toString(), command.position().financialVersion(),
                json.write(operation.input()), command.digest(), json.write(operation), command.position().reportId().toString(),
                timestamp(operation.createdAt()), timestamp(operation.updatedAt()), timestamp(operation.nextAttemptAt()));
        append(operation);
    }

    /** 领取版本充当围栏；原命令、目标和摘要不能因重试而改写。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetOperation operation) {
        var command = operation.input().command();
        int updated = jdbc.update("""
                UPDATE budget_operation SET version=?,status=?,attempts=?,active_report_id=?,state_json=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND command_digest=?
                """, operation.version(), operation.status().name(), operation.attempts(), operation.terminal() ? null : command.position().reportId().toString(),
                json.write(operation), timestamp(operation.updatedAt()), timestamp(operation.nextAttemptAt()), timestamp(operation.leaseUntil()),
                command.tenantId(), command.id().toString(), operation.version() - 1, json.write(operation.input()), command.digest());
        if (updated != 1) throw conflict();
        append(operation);
    }

    /** 租户和命令归属由索引列与完整快照共同验证。 */
    public Optional<BudgetOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM budget_operation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }

    /** 费用详情只展示同单最近操作，完整输入与外部凭据不返回到页面。 */
    public Optional<BudgetOperation> latest(String tenant, UUID reportId) {
        return jdbc.query("SELECT * FROM budget_operation WHERE tenant_id=? AND report_id=? ORDER BY financial_version DESC,created_at DESC,id DESC LIMIT 1",
                row(), tenant, reportId.toString()).stream().findFirst();
    }

    /** 到期恢复最多十条，重试退避在持久状态中，不随进程重启重置。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM budget_operation
                WHERE (status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?) OR (status IN ('EXECUTING','QUERYING') AND lease_until<=?)
                ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now), timestamp(now));
    }

    private RowMapper<BudgetOperation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetOperation.class); var command = value.input().command();
            if (!value.input().equals(json.read(row.getString("input_json"), BudgetOperation.Input.class))
                    || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                    || !command.position().reportId().toString().equals(row.getString("report_id")) || command.position().financialVersion() != row.getLong("financial_version")
                    || !command.digest().equals(row.getString("command_digest")) || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                    || !Objects.equals(value.terminal() ? null : command.position().reportId().toString(), row.getString("active_report_id"))
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) {
                throw new IllegalStateException("Persisted budget operation identity is inconsistent");
            }
            return value;
        };
    }
    private void append(BudgetOperation value) {
        jdbc.update("INSERT INTO budget_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)",
                value.input().command().tenantId(), value.input().command().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget operation input or version changed"); }

    /**
     * 扫描不加载财务数据，取得单据锁后再读取完整操作。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
