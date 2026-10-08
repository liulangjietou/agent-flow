package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Document;
import io.agentflow.expense.mapper.ExpenseSplitRoutingRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 在费用提交事务内读取当前来源并追加原轮次依据，没有覆盖历史快照的更新入口。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseSplitRoutingRepository {
    private final ExpenseSplitRoutingRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 复用业务 JDBC 事务和统一 JSON 格式。 */
    public JdbcExpenseSplitRoutingRepository(
            ExpenseSplitRoutingRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 当前轮次只查本次提交的费用范围，参与类别由主单正核定额确定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Document> candidates(
            String tenantId,
            String employeeId,
            UUID legalEntityId,
            String currency,
            UUID excludedReport,
            Instant from,
            Instant through,
            Set<String> categories) {
        if (categories.isEmpty()) return List.of();
        var documents = new ArrayList<Document>();
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        // 先缩小当前窗口，再流式筛选参与类别；不对其他报销单加锁，不截断仍可能影响结果的记录。
        sqlMapper.candidateRows(
                new Object[] {
                    tenantId,
                    employeeId,
                    excludedReport.toString(),
                    legalEntityId.toString(),
                    currency,
                    Timestamp.from(from),
                    Timestamp.from(through)
                },
                context -> {
                    try {
                        var row = context.getResultObject();
                        var report = JdbcExpenseReportRepository.restoreCurrent(row, json);
                        var status = ApplicationStatus.valueOf(row.getString("candidate_status"));
                        var document =
                                Document.from(
                                        report,
                                        row.getLong("candidate_application_version"),
                                        status);
                        if (document.roundNo() != row.getInt("candidate_round_no")
                                || document.roundNo() != row.getInt("candidate_frozen_round")
                                || !status.name().equals(row.getString("candidate_frozen_status")))
                            throw inconsistent();
                        if (document.submittedAt().isBefore(from)
                                || document.submittedAt().isAfter(through)
                                || document.lines().stream()
                                        .noneMatch(
                                                line ->
                                                        categories.contains(line.categoryCode())
                                                                && line.approvedGross()
                                                                                .value()
                                                                                .signum()
                                                                        > 0)) return;
                        if (documents.size() == ExpenseSplitRiskEvidence.MAX_DOCUMENTS - 1) {
                            throw new DomainException(
                                    "EXPENSE_SPLIT_SOURCE_LIMIT",
                                    "Split risk source count exceeds the supported limit");
                        }
                        documents.add(document);
                    } catch (RuntimeException invalid) {
                        failure.set(invalid);
                        context.stop();
                    }
                });
        if (failure.get() != null) throw failure.get();
        return List.copyOf(documents);
    }

    /** 主单审批轮次尚未追加，先绑定真实费用修订和预期轮次；后续失败一并回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseSplitRoutingSnapshot snapshot) {
        var primary = snapshot.primary();
        int inserted =
                sqlMapper.save(
                        snapshot.roundNo(),
                        snapshot.ruleVersion(),
                        snapshot.configuration().mode().name(),
                        Timestamp.from(primary.submittedAt().truncatedTo(ChronoUnit.MICROS)),
                        json.write(snapshot),
                        snapshot.tenantId(),
                        snapshot.reportId().toString(),
                        snapshot.applicationId().toString(),
                        primary.financialVersion(),
                        primary.applicationVersion(),
                        snapshot.roundNo(),
                        primary.status().name(),
                        snapshot.roundNo(),
                        snapshot.definitionId().toString(),
                        snapshot.processKey(),
                        snapshot.definitionVersion());
        if (inserted != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT",
                    "Split routing preparation requires the original editable application,"
                            + " financial revision and published definition");
        for (int ordinal = 1; ordinal < snapshot.sources().size(); ordinal++) {
            var document = snapshot.sources().get(ordinal);
            sqlMapper.save2(
                    snapshot.tenantId(),
                    snapshot.reportId().toString(),
                    snapshot.roundNo(),
                    ordinal,
                    document.reportId().toString(),
                    document.applicationId().toString(),
                    document.applicationVersion(),
                    document.financialVersion(),
                    document.roundNo(),
                    json.write(document));
        }
        // 用原财务修订校验完整来源；新主单轮次将在同一事务的引擎启动之后追加。
        if (!find(snapshot.tenantId(), snapshot.reportId(), snapshot.roundNo())
                .filter(snapshot::equals)
                .isPresent()) throw inconsistent();
    }

    /** 只读取指定原轮次，不因后续核减或来源撤回改写历史结论。 */
    public Optional<ExpenseSplitRoutingSnapshot> find(String tenantId, UUID reportId, int roundNo) {
        return SqlRows.map(sqlMapper.find(tenantId, reportId.toString(), roundNo), this::restore)
                .stream()
                .findFirst();
    }

    /** 引擎启动只使用同一申请和预期轮次已准备的服务端依据。 */
    public Optional<ExpenseSplitRoutingSnapshot> findByApplication(
            String tenantId, UUID applicationId, int roundNo) {
        return SqlRows.map(
                        sqlMapper.findByApplication(tenantId, applicationId.toString(), roundNo),
                        this::restore)
                .stream()
                .findFirst();
    }

    private ExpenseSplitRoutingSnapshot restore(SqlRow row) {
        var snapshot = json.read(row.getString("snapshot_json"), ExpenseSplitRoutingSnapshot.class);
        var primary = snapshot.primary();
        if (!snapshot.tenantId().equals(row.getString("tenant_id"))
                || !snapshot.reportId().toString().equals(row.getString("report_id"))
                || !snapshot.applicationId().toString().equals(row.getString("application_id"))
                || snapshot.roundNo() != row.getInt("round_no")
                || primary.applicationVersion() != row.getLong("application_version")
                || primary.financialVersion() != row.getLong("financial_version")
                || !snapshot.definitionId().toString().equals(row.getString("definition_id"))
                || !snapshot.processKey().equals(row.getString("process_key"))
                || snapshot.definitionVersion() != row.getLong("definition_version")
                || snapshot.ruleVersion() != row.getInt("rule_version")
                || !snapshot.configuration().mode().name().equals(row.getString("mode"))
                || !primary.submittedAt()
                        .truncatedTo(ChronoUnit.MICROS)
                        .equals(row.getTimestamp("submitted_at").toInstant())) throw inconsistent();
        requireFinancialState(primary, row.getString("financial_state_json"));
        var sources =
                SqlRows.map(
                        sqlMapper.restore(
                                snapshot.tenantId(),
                                snapshot.reportId().toString(),
                                snapshot.roundNo()),
                        (source, ordinal) -> restoreSource(source, ordinal + 1));
        if (!sources.equals(snapshot.sources().subList(1, snapshot.sources().size())))
            throw inconsistent();
        return snapshot;
    }

    private Document restoreSource(SqlRow row, int ordinal) {
        var document = json.read(row.getString("document_json"), Document.class);
        if (ordinal != row.getInt("ordinal") || !document.scope().tenantId().equals(row.getString("tenant_id"))
                || !document.reportId().toString().equals(row.getString("source_report_id"))
                || !document.applicationId().toString().equals(row.getString("source_application_id"))
                || document.applicationVersion() != row.getLong("source_application_version") || document.financialVersion() != row.getLong("source_financial_version")
                || document.roundNo() != row.getInt("source_round_no")) throw inconsistent();
        requireFinancialState(document, row.getString("financial_state_json"));
        return document;
    }

    private void requireFinancialState(Document document, String financialState) {
        var report = ExpenseReport.restore(json.read(financialState, ExpenseReport.State.class));
        if (!document.equals(Document.from(report, document.applicationVersion(), document.status()))) throw inconsistent();
    }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted expense split routing binding is inconsistent"); }
}
