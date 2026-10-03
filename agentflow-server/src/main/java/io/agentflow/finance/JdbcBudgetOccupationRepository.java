package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 预算事实与单据本人绑定，单据锁协调并发，乐观版本和审计保证连续写入。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcBudgetOccupationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与报销及预算任务共用本地事务。 */
    public JdbcBudgetOccupationRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 首次登记必须指向真实报销本人，不能先创建孤立的预算台账。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(BudgetOccupation occupation) {
        if (occupation.version() != 1 || occupation.status() != BudgetOccupation.Status.UNFUNDED || occupation.pendingOperationId() == null) throw conflict();
        int inserted = jdbc.update("""
                INSERT INTO budget_occupation(tenant_id,report_id,employee_id,application_id,target_digest,version,status,pending_operation_id,state_json)
                SELECT tenant_id,id,employee_id,application_id,?,1,'UNFUNDED',?,? FROM expense_report
                WHERE tenant_id=? AND id=? AND employee_id=?
                """, occupation.targetDigest(), occupation.pendingOperationId().toString(), json.write(occupation), occupation.tenantId(),
                occupation.reportId().toString(), occupation.employeeId());
        if (inserted != 1) throw conflict();
        append(occupation);
    }

    /** 原始归属和目标不可更换，每个新状态单独追加审计。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(BudgetOccupation occupation) {
        int updated = jdbc.update("""
                UPDATE budget_occupation SET version=?,status=?,pending_operation_id=?,state_json=?
                WHERE tenant_id=? AND report_id=? AND employee_id=? AND target_digest=? AND version=?
                """, occupation.version(), occupation.status().name(), occupation.pendingOperationId() == null ? null : occupation.pendingOperationId().toString(),
                json.write(occupation), occupation.tenantId(), occupation.reportId().toString(), occupation.employeeId(), occupation.targetDigest(), occupation.version() - 1);
        if (updated != 1) throw conflict();
        append(occupation);
    }

    /** 读取时验证索引列与完整快照相符，防止错误台账参与审批守卫。 */
    public Optional<BudgetOccupation> find(String tenant, UUID reportId) {
        return jdbc.query("SELECT * FROM budget_occupation WHERE tenant_id=? AND report_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), BudgetOccupation.class);
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.reportId().toString().equals(row.getString("report_id"))
                    || !value.employeeId().equals(row.getString("employee_id")) || !value.targetDigest().equals(row.getString("target_digest"))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status"))
                    || !Objects.equals(value.pendingOperationId() == null ? null : value.pendingOperationId().toString(), row.getString("pending_operation_id"))) {
                throw new IllegalStateException("Persisted budget occupation identity is inconsistent");
            }
            return value;
        }, tenant, reportId.toString()).stream().findFirst();
    }
    private void append(BudgetOccupation value) {
        jdbc.update("INSERT INTO budget_occupation_revision(tenant_id,report_id,version,state_json) VALUES(?,?,?,?)",
                value.tenantId(), value.reportId().toString(), value.version(), json.write(value));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Budget occupation identity or version changed"); }
}
