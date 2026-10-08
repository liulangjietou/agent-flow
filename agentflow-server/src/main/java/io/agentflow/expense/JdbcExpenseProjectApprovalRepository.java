package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseProjectApprovalRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 提交事务追加原项目依据；通过已有不可变修订恢复来源，不读取后来目录替换负责人。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseProjectApprovalRepository {
    private final ExpenseProjectApprovalRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 复用业务数据库和统一 JSON，不另建项目审批状态。 */
    public JdbcExpenseProjectApprovalRepository(
            ExpenseProjectApprovalRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 原财务已冻结、申请已修订而引擎尚未启动；任一来源不一致或后续失败均回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseProjectApprovalSnapshot snapshot) {
        var input = snapshot.precheck();
        int inserted =
                sqlMapper.save(
                        snapshot.roundNo(),
                        snapshot.ruleVersion(),
                        snapshot.nodeId(),
                        snapshot.owners().catalogVersion(),
                        snapshot.hasProjects(),
                        Timestamp.from(snapshot.submittedAt()),
                        json.write(snapshot),
                        snapshot.tenantId(),
                        snapshot.reportId().toString(),
                        snapshot.applicationId().toString(),
                        input.employeeId(),
                        snapshot.financialVersion(),
                        snapshot.applicationVersion(),
                        snapshot.roundNo(),
                        snapshot.roundNo(),
                        snapshot.definitionId().toString(),
                        snapshot.processKey(),
                        snapshot.definitionVersion(),
                        input.id().toString(),
                        snapshot.precheckVersion(),
                        input.applicationVersion(),
                        input.financialVersion());
        if (inserted != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Project evidence requires the original editable application, frozen financial"
                        + " revision, ready precheck and published definition");
        if (!find(snapshot.tenantId(), snapshot.reportId(), snapshot.roundNo())
                .filter(snapshot::equals)
                .isPresent()) throw inconsistent();
    }

    /** 历史缺记录保持未记录，不按当前项目目录生成替代依据。 */
    public Optional<ExpenseProjectApprovalSnapshot> find(
            String tenantId, UUID reportId, int roundNo) {
        return SqlRows.map(sqlMapper.find(tenantId, reportId.toString(), roundNo), this::restore)
                .stream()
                .findFirst();
    }

    /** 引擎启动和责任查询只使用该申请原轮次已有的来源。 */
    public Optional<ExpenseProjectApprovalSnapshot> findByApplication(
            String tenantId, UUID applicationId, int roundNo) {
        return SqlRows.map(
                        sqlMapper.findByApplication(tenantId, applicationId.toString(), roundNo),
                        this::restore)
                .stream()
                .findFirst();
    }

    private ExpenseProjectApprovalSnapshot restore(SqlRow row) {
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
        return new IllegalStateException(
                "Persisted project approval evidence does not match its original precheck,"
                    + " financial revision or definition");
    }
}
