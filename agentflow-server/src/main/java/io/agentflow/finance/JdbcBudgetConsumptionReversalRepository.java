package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseResourceAdjustment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算实际冲正的独立 outbox，原消费修订和已授权报销调整必须先存在。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetConsumptionReversalRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    public JdbcBudgetConsumptionReversalRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 初次只登记原授权的排队命令，不允许从快照直接制造外部成功。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetConsumptionReversalOperation value) {
        var input = value.input(); var command = input.command(); var tenant = command.source().tenantId();
        if (!value.equals(BudgetConsumptionReversalOperation.queue(input, value.createdAt())) || !command.id().equals(command.adjustmentId())) throw conflict();
        var adjustment = jdbc.query("SELECT state_json FROM expense_resource_adjustment WHERE tenant_id=? AND id=?",
                (row, index) -> json.read(row.getString("state_json"), ExpenseResourceAdjustment.class), tenant, command.adjustmentId().toString()).stream().findFirst().orElseThrow(JdbcBudgetConsumptionReversalRepository::conflict);
        if (!adjustment.input().budget().equals(input) || adjustment.status() != ExpenseResourceAdjustment.Status.WAITING_BUDGET || adjustment.version() != 1) throw conflict();
        jdbc.update("""
                INSERT INTO budget_consumption_reversal_operation(tenant_id,id,consumption_id,consumed_version,input_json,command_digest,state_json,
                version,status,attempts,created_at,updated_at,next_attempt_at)
                VALUES(?,?,?,?,?,?,?,1,'QUEUED',0,?,?,?)
                """, tenant, command.id().toString(), command.source().id().toString(), input.consumedVersion(), json.write(input), command.digest(), json.write(value),
                timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()));
        append(value);
    }
    /** 原命令与目标保持不变，已安全结束的办理不可被迟到工作器重新执行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetConsumptionReversalOperation value) {
        var input = value.input(); var command = input.command();
        int changed = jdbc.update("""
                UPDATE budget_consumption_reversal_operation SET state_json=?,version=?,status=?,attempts=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND command_digest=? AND created_at=?
                AND EXISTS (SELECT 1 FROM expense_resource_adjustment a WHERE a.tenant_id=? AND a.id=? AND a.status<>'RETIRED')
                """, json.write(value), value.version(), value.status().name(), value.attempts(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()),
                command.source().tenantId(), command.id().toString(), value.version() - 1, json.write(input), command.digest(), timestamp(value.createdAt()),
                command.source().tenantId(), command.adjustmentId().toString());
        if (changed != 1) throw conflict(); append(value);
    }
    public Optional<BudgetConsumptionReversalOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM budget_consumption_reversal_operation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 接受的预算结果和结束证明引用确切修订，不能被当前结果覆盖。 */
    public Optional<BudgetConsumptionReversalOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM budget_consumption_reversal_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?",
                (row, index) -> {
                    var value = json.read(row.getString("state_json"), BudgetConsumptionReversalOperation.class);
                    if (!value.input().command().source().tenantId().equals(tenant) || !value.input().command().id().equals(id) || value.version() != version) throw inconsistent();
                    return value;
                }, tenant, id.toString(), version).stream().findFirst();
    }
    /** 未知按持久退避查询，租约超时不得被扫描器改成首次发送。 */
    public List<Candidate> due(Instant at) {
        return jdbc.query("""
                SELECT tenant_id,id FROM budget_consumption_reversal_operation
                WHERE (status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?) OR (status IN ('EXECUTING','QUERYING') AND lease_until<=?)
                ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(at), timestamp(at));
    }
    private RowMapper<BudgetConsumptionReversalOperation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetConsumptionReversalOperation.class); var input = value.input(); var command = input.command();
            if (!input.equals(json.read(row.getString("input_json"), BudgetConsumptionReversalOperation.Input.class))
                    || !command.source().tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id")) || !command.id().equals(command.adjustmentId())
                    || !command.source().id().toString().equals(row.getString("consumption_id")) || input.consumedVersion() != row.getLong("consumed_version")
                    || !command.digest().equals(row.getString("command_digest")) || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts") || !value.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant()) || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw inconsistent();
            return value;
        };
    }
    private void append(BudgetConsumptionReversalOperation value) {
        var command = value.input().command();
        jdbc.update("INSERT INTO budget_consumption_reversal_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)",
                command.source().tenantId(), command.id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget reversal input, adjustment or version changed"); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted budget consumption reversal identity is inconsistent"); }
    /**
     * 扫描只传固定租户和命令编号。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
