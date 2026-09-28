package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * 正式提交控制与纸件事实按轮次存储，预检、实际审批轮次和财务版本共同约束来源。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseSubmissionControlRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 与审批启动、资源占用及预算登记共用事务。 */
    public JdbcExpenseSubmissionControlRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 只有本单 READY 预检紧接着冻结的财务版本能够建立正式控制记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseSubmissionControl control) {
        var input = control.input();
        if (control.version() != 1 || control.receipt() != null) throw conflict();
        int inserted = jdbc.update("""
                INSERT INTO expense_submission_control(tenant_id,report_id,application_id,employee_id,round_no,
                submitted_financial_version,precheck_id,paper_required,receipt_received,version,input_json,state_json,submitted_at)
                SELECT tenant_id,report_id,application_id,employee_id,?,financial_version+1,id,?,FALSE,1,?,?,?
                FROM expense_precheck_job WHERE tenant_id=? AND report_id=? AND application_id=? AND employee_id=? AND id=?
                AND status='READY' AND financial_version=?
                """, input.roundNo(), input.paperReceiptRequired(), json.write(input), json.write(control), Timestamp.from(control.submittedAt()),
                input.tenantId(), input.reportId().toString(), input.applicationId().toString(), input.employeeId(), input.precheckId().toString(), input.submittedFinancialVersion() - 1);
        if (inserted != 1) throw conflict();
        append(control);
    }

    /** 原提交输入始终不变，一次纸件确认追加第二版本。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseSubmissionControl control) {
        var input = control.input();
        if (control.version() != 2 || control.receipt() == null) throw conflict();
        int updated = jdbc.update("""
                UPDATE expense_submission_control SET version=2,receipt_received=TRUE,state_json=?
                WHERE tenant_id=? AND report_id=? AND round_no=? AND version=1 AND input_json=?
                """, json.write(control), input.tenantId(), input.reportId().toString(), input.roundNo(), json.write(input));
        if (updated != 1) throw conflict();
        append(control);
    }

    /** 读取索引和快照双重身份，不把其他轮次的纸件状态拼入当前申请。 */
    public Optional<ExpenseSubmissionControl> find(String tenant, UUID reportId, int roundNo) {
        return jdbc.query("SELECT * FROM expense_submission_control WHERE tenant_id=? AND report_id=? AND round_no=?", (row, index) -> {
            var control = json.read(row.getString("state_json"), ExpenseSubmissionControl.class); var input = control.input();
            if (!input.equals(json.read(row.getString("input_json"), ExpenseSubmissionControl.Input.class))
                    || !input.tenantId().equals(row.getString("tenant_id")) || !input.reportId().toString().equals(row.getString("report_id"))
                    || !input.applicationId().toString().equals(row.getString("application_id")) || !input.employeeId().equals(row.getString("employee_id"))
                    || input.roundNo() != row.getInt("round_no") || input.submittedFinancialVersion() != row.getLong("submitted_financial_version")
                    || !input.precheckId().toString().equals(row.getString("precheck_id")) || input.paperReceiptRequired() != row.getBoolean("paper_required")
                    || (control.receipt() != null) != row.getBoolean("receipt_received") || control.version() != row.getLong("version")
                    || !control.submittedAt().equals(row.getTimestamp("submitted_at").toInstant())) {
                throw new IllegalStateException("Persisted expense submission control is inconsistent");
            }
            return control;
        }, tenant, reportId.toString(), roundNo).stream().findFirst();
    }
    private void append(ExpenseSubmissionControl value) {
        var input = value.input();
        jdbc.update("INSERT INTO expense_submission_control_revision(tenant_id,report_id,round_no,version,state_json) VALUES(?,?,?,?,?)",
                input.tenantId(), input.reportId().toString(), input.roundNo(), value.version(), json.write(value));
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense submission control or precheck changed"); }
}
