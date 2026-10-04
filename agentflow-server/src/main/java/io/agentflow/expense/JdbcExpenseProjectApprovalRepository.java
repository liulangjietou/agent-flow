package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交事务追加原项目依据；通过已有不可变修订恢复来源，不读取后来目录替换负责人。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseProjectApprovalRepository {
    private static final String QUERY = """
            SELECT p.*,f.state_json AS financial_state_json,c.state_json AS precheck_state_json,
                d.process_key AS original_process_key,d.version AS original_definition_version
            FROM expense_project_approval p
            JOIN expense_report_revision f ON f.tenant_id=p.tenant_id AND f.report_id=p.report_id AND f.financial_version=p.financial_version
            JOIN expense_precheck_revision c ON c.tenant_id=p.tenant_id AND c.job_id=p.precheck_id AND c.version=p.precheck_version
            JOIN approval_definition d ON d.tenant_id=p.tenant_id AND d.id=p.definition_id
            """;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 复用业务数据库和统一 JSON，不另建项目审批状态。 */
    public JdbcExpenseProjectApprovalRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 原财务已冻结、申请已修订而引擎尚未启动；任一来源不一致或后续失败均回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseProjectApprovalSnapshot snapshot) {
        var input = snapshot.precheck();
        int inserted = jdbc.update("""
                INSERT INTO expense_project_approval(tenant_id,report_id,round_no,application_id,application_version,financial_version,
                    definition_id,process_key,definition_version,rule_version,node_id,precheck_id,precheck_version,catalog_version,has_projects,submitted_at,snapshot_json)
                SELECT e.tenant_id,e.id,?,a.id,a.version,e.version,d.id,d.process_key,d.version,?,?,c.id,c.version,?,?,?,?
                FROM expense_report e JOIN approval_application a ON a.tenant_id=e.tenant_id AND a.id=e.application_id
                JOIN approval_definition d ON d.tenant_id=a.tenant_id AND d.process_key=a.process_key AND d.version=a.definition_version
                JOIN expense_precheck_job c ON c.tenant_id=e.tenant_id AND c.report_id=e.id AND c.application_id=a.id AND c.employee_id=e.employee_id
                WHERE e.tenant_id=? AND e.id=? AND a.id=? AND e.employee_id=? AND e.version=? AND a.version=? AND e.current_round_no=?
                    AND a.status IN ('DRAFT','RETURNED','WITHDRAWN') AND a.business_type='EXPENSE' AND a.business_id=e.id
                    AND (CASE WHEN a.status='DRAFT' THEN a.round_no ELSE a.round_no+1 END)=?
                    AND d.id=? AND d.process_key=? AND d.version=? AND d.status='PUBLISHED'
                    AND c.id=? AND c.version=? AND c.status='READY' AND c.application_version=? AND c.financial_version=?
                """, snapshot.roundNo(), snapshot.ruleVersion(), snapshot.nodeId(), snapshot.owners().catalogVersion(), snapshot.hasProjects(),
                Timestamp.from(snapshot.submittedAt()), json.write(snapshot), snapshot.tenantId(), snapshot.reportId().toString(), snapshot.applicationId().toString(),
                input.employeeId(), snapshot.financialVersion(), snapshot.applicationVersion(), snapshot.roundNo(), snapshot.roundNo(),
                snapshot.definitionId().toString(), snapshot.processKey(), snapshot.definitionVersion(), input.id().toString(), snapshot.precheckVersion(),
                input.applicationVersion(), input.financialVersion());
        if (inserted != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Project evidence requires the original editable application, frozen financial revision, ready precheck and published definition");
        if (!find(snapshot.tenantId(), snapshot.reportId(), snapshot.roundNo()).filter(snapshot::equals).isPresent()) throw inconsistent();
    }

    /** 历史缺记录保持未记录，不按当前项目目录生成替代依据。 */
    public Optional<ExpenseProjectApprovalSnapshot> find(String tenantId, UUID reportId, int roundNo) {
        return jdbc.query(QUERY + " WHERE p.tenant_id=? AND p.report_id=? AND p.round_no=?", this::restore,
                tenantId, reportId.toString(), roundNo).stream().findFirst();
    }

    /** 引擎启动和责任查询只使用该申请原轮次已有的来源。 */
    public Optional<ExpenseProjectApprovalSnapshot> findByApplication(String tenantId, UUID applicationId, int roundNo) {
        return jdbc.query(QUERY + " WHERE p.tenant_id=? AND p.application_id=? AND p.round_no=?", this::restore,
                tenantId, applicationId.toString(), roundNo).stream().findFirst();
    }

    private ExpenseProjectApprovalSnapshot restore(ResultSet row, int index) throws SQLException {
        var snapshot = json.read(row.getString("snapshot_json"), ExpenseProjectApprovalSnapshot.class);
        if (!snapshot.tenantId().equals(row.getString("tenant_id")) || !snapshot.reportId().toString().equals(row.getString("report_id"))
                || !snapshot.applicationId().toString().equals(row.getString("application_id")) || snapshot.roundNo() != row.getInt("round_no")
                || snapshot.applicationVersion() != row.getLong("application_version") || snapshot.financialVersion() != row.getLong("financial_version")
                || !snapshot.definitionId().toString().equals(row.getString("definition_id")) || !snapshot.processKey().equals(row.getString("process_key"))
                || !snapshot.processKey().equals(row.getString("original_process_key")) || snapshot.definitionVersion() != row.getLong("definition_version")
                || snapshot.definitionVersion() != row.getLong("original_definition_version") || snapshot.ruleVersion() != row.getInt("rule_version")
                || !Objects.equals(snapshot.nodeId(), row.getString("node_id")) || !snapshot.precheck().id().toString().equals(row.getString("precheck_id"))
                || snapshot.precheckVersion() != row.getLong("precheck_version") || !snapshot.owners().catalogVersion().equals(row.getString("catalog_version"))
                || snapshot.hasProjects() != row.getBoolean("has_projects") || !snapshot.submittedAt().equals(row.getTimestamp("submitted_at").toInstant())) throw inconsistent();
        var report = ExpenseReport.restore(json.read(row.getString("financial_state_json"), ExpenseReport.State.class));
        var precheck = json.read(row.getString("precheck_state_json"), ExpensePrecheckJob.class);
        if (!snapshot.matches(report, precheck)) throw inconsistent();
        return snapshot;
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException("Persisted project approval evidence does not match its original precheck, financial revision or definition");
    }
}
