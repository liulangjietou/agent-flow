package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePriorControlRepositoryMapper;
import io.agentflow.form.FormSchema;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 额度控制只追加原轮次事实，并引用真正预留前后的不可变资源修订供恢复复算。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePriorControlRepository {
    private final ExpensePriorControlRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共享提交事务和已有资源修订，不复制其他报销单的明细。 */
    public JdbcExpensePriorControlRepository(
            ExpensePriorControlRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 费用、资源及派生载荷已写入，审批轮次还未启动；任一后续失败均回滚本次记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpensePriorControlSnapshot snapshot, ExpenseSubmissionResources.Plan plan) {
        int inserted =
                sqlMapper.save(
                        snapshot.roundNo(),
                        Timestamp.from(snapshot.submittedAt()),
                        snapshot.requiresApproval(),
                        json.write(snapshot),
                        snapshot.tenantId(),
                        snapshot.reportId().toString(),
                        snapshot.applicationId().toString(),
                        snapshot.financialVersion(),
                        snapshot.applicationVersion(),
                        snapshot.roundNo(),
                        snapshot.roundNo(),
                        snapshot.definitionId().toString(),
                        snapshot.definitionVersion());
        if (inserted != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Prior control preparation requires the original editable submission");
        var finalStates = new HashMap<UUID, ExpenseRequest.State>();
        plan.requests().forEach(change -> finalStates.put(change.after().id(), change.after()));
        var versions = new HashMap<UUID, Long>();
        for (var assessment : snapshot.assessments()) {
            var previous =
                    versions.putIfAbsent(assessment.requestId(), assessment.requestVersion());
            if (previous != null) {
                if (previous != assessment.requestVersion()) throw inconsistent();
                continue;
            }
            var after = finalStates.get(assessment.requestId());
            if (after == null) throw inconsistent();
            sqlMapper.save2(
                    snapshot.tenantId(),
                    snapshot.reportId().toString(),
                    snapshot.roundNo(),
                    assessment.requestId().toString(),
                    assessment.requestVersion(),
                    after.version());
        }
        if (!find(snapshot.tenantId(), snapshot.reportId(), snapshot.roundNo())
                .filter(snapshot::equals)
                .isPresent()) throw inconsistent();
    }

    /** 历史事实按原财务修订和原资源修订复算，不读取后来发生的其他占用。 */
    public Optional<ExpensePriorControlSnapshot> find(String tenant, UUID reportId, int roundNo) {
        return SqlRows.map(sqlMapper.find(tenant, reportId.toString(), roundNo), this::restore)
                .stream()
                .findFirst();
    }

    /** 核减和引擎绑定均使用原提交结论；新字段缺原依据时不能默认为未超额。 */
    public Boolean routingFlag(ExpenseReport report, FormSchema schema) {
        if (!ExpenseFormContract.hasPriorControl(schema)) return null;
        return find(report.tenantId(), report.id(), report.currentRound().roundNo())
                .orElseThrow(
                        () ->
                                new DomainException(
                                        "EXPENSE_PRIOR_CONTROL_MISSING",
                                        "Original prior control evidence is required for this"
                                            + " expense round"))
                .requiresApproval();
    }

    private ExpensePriorControlSnapshot restore(SqlRow row) {
        var snapshot = json.read(row.getString("snapshot_json"), ExpensePriorControlSnapshot.class);
        if (!snapshot.tenantId().equals(row.getString("tenant_id"))
                || !snapshot.reportId().toString().equals(row.getString("report_id"))
                || snapshot.roundNo() != row.getInt("round_no")
                || !snapshot.applicationId().toString().equals(row.getString("application_id"))
                || snapshot.applicationVersion() != row.getLong("application_version")
                || snapshot.financialVersion() != row.getLong("financial_version")
                || !snapshot.definitionId().toString().equals(row.getString("definition_id"))
                || snapshot.definitionVersion() != row.getLong("definition_version")
                || snapshot.definitionVersion() != row.getLong("original_definition_version")
                || snapshot.requiresApproval() != row.getBoolean("requires_approval")
                || !snapshot.submittedAt().equals(row.getTimestamp("submitted_at").toInstant()))
            throw inconsistent();
        var report =
                ExpenseReport.restore(
                        json.read(
                                row.getString("financial_state_json"), ExpenseReport.State.class));
        if (!snapshot.tenantId().equals(report.tenantId())
                || !snapshot.reportId().equals(report.id())
                || !snapshot.applicationId().equals(report.applicationId())
                || snapshot.financialVersion() != report.version()
                || snapshot.roundNo() != report.currentRound().roundNo()
                || !snapshot.submittedAt().equals(report.currentRound().submittedAt())
                || !report.currentRound().adjustments().isEmpty()) throw inconsistent();
        var before = new HashMap<UUID, ExpenseRequest.State>();
        var after = new HashMap<UUID, ExpenseRequest.State>();
        sqlMapper
                .restore(snapshot.tenantId(), snapshot.reportId().toString(), snapshot.roundNo())
                .forEach(
                        source -> {
                            UUID id = UUID.fromString(source.getString("request_id"));
                            before.put(
                                    id,
                                    source(
                                            source.getString("before_json"),
                                            report,
                                            id,
                                            source.getLong("before_version")));
                            after.put(
                                    id,
                                    source(
                                            source.getString("after_json"),
                                            report,
                                            id,
                                            source.getLong("after_version")));
                        });
        var ids =
                snapshot.assessments().stream()
                        .map(ExpensePriorControlAssessment::requestId)
                        .collect(Collectors.toSet());
        if (!ids.equals(before.keySet())
                || !snapshot.assessments()
                        .equals(
                                ExpensePriorControlAssessment.evaluate(
                                        report.id(), report.currentRound(), before, after)))
            throw inconsistent();
        return snapshot;
    }

    private ExpenseRequest.State source(String raw, ExpenseReport report, UUID id, long version) {
        var source = ExpenseRequest.restore(json.read(raw, ExpenseRequest.State.class));
        if (!source.tenantId().equals(report.tenantId()) || !source.id().equals(id) || source.version() != version
                || !source.employeeId().equals(report.employeeId()) || !source.legalEntityId().equals(report.content().legalEntityId())) throw inconsistent();
        return source.state();
    }

    private static IllegalStateException inconsistent() {
        return new IllegalStateException(
                "Persisted prior control does not match the original financial and resource"
                    + " revisions");
    }
}
