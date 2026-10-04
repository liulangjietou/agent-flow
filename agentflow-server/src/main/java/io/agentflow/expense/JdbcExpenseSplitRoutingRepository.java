package io.agentflow.expense;

import io.agentflow.common.JsonUtil;
import io.agentflow.common.DomainException;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Document;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在费用提交事务内读取当前来源并追加原轮次依据，没有覆盖历史快照的更新入口。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseSplitRoutingRepository {
    private static final int FETCH_SIZE = 128;
    private static final String SNAPSHOT_QUERY = """
            SELECT r.*,v.state_json AS financial_state_json FROM expense_split_routing r
            JOIN expense_report_revision v ON v.tenant_id=r.tenant_id AND v.report_id=r.report_id AND v.financial_version=r.financial_version
            """;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;

    /** 复用业务 JDBC 事务和统一 JSON 格式。 */
    public JdbcExpenseSplitRoutingRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 当前轮次只查本次提交的费用范围，参与类别由主单正核定额确定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Document> candidates(String tenantId, String employeeId, UUID legalEntityId, String currency,
            UUID excludedReport, Instant from, Instant through, Set<String> categories) {
        if (categories.isEmpty()) return List.of();
        var documents = new ArrayList<Document>();
        // 先缩小当前窗口，再流式筛选参与类别；不对其他报销单加锁，不截断仍可能影响结果的记录。
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT e.*,a.version AS candidate_application_version,a.status AS candidate_status,a.round_no AS candidate_round_no,
                        s.round_no AS candidate_frozen_round,s.status AS candidate_frozen_status
                    FROM expense_report e JOIN approval_application a ON a.tenant_id=e.tenant_id AND a.id=e.application_id
                    LEFT JOIN approval_submission_round s ON s.tenant_id=a.tenant_id AND s.application_id=a.id AND s.round_no=a.round_no
                    WHERE e.tenant_id=? AND e.employee_id=? AND e.id<>? AND a.status IN ('IN_APPROVAL','APPROVED')
                        AND (e.current_round_no IS NULL OR (e.current_legal_entity_id=? AND e.current_base_currency=?
                            AND e.current_submitted_at>=? AND e.current_submitted_at<=?))
                    ORDER BY e.current_submitted_at,e.id
                    """);
            statement.setFetchSize(FETCH_SIZE); statement.setString(1, tenantId); statement.setString(2, employeeId);
            statement.setString(3, excludedReport.toString()); statement.setString(4, legalEntityId.toString()); statement.setString(5, currency);
            statement.setTimestamp(6, Timestamp.from(from)); statement.setTimestamp(7, Timestamp.from(through));
            return statement;
        }, (RowCallbackHandler) row -> {
            var report = JdbcExpenseReportRepository.restoreCurrent(row, json);
            var status = ApplicationStatus.valueOf(row.getString("candidate_status"));
            var document = Document.from(report, row.getLong("candidate_application_version"), status);
            if (document.roundNo() != row.getInt("candidate_round_no") || document.roundNo() != row.getInt("candidate_frozen_round")
                    || !status.name().equals(row.getString("candidate_frozen_status"))) throw inconsistent();
            if (document.submittedAt().isBefore(from) || document.submittedAt().isAfter(through)
                    || document.lines().stream().noneMatch(line -> categories.contains(line.categoryCode()) && line.approvedGross().value().signum() > 0)) return;
            if (documents.size() == ExpenseSplitRiskEvidence.MAX_DOCUMENTS - 1) {
                throw new DomainException("EXPENSE_SPLIT_SOURCE_LIMIT", "Split risk source count exceeds the supported limit");
            }
            documents.add(document);
        });
        return List.copyOf(documents);
    }

    /** 主单审批轮次尚未追加，先绑定真实费用修订和预期轮次；后续失败一并回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(ExpenseSplitRoutingSnapshot snapshot) {
        var primary = snapshot.primary();
        int inserted = jdbc.update("""
                INSERT INTO expense_split_routing(tenant_id,report_id,round_no,application_id,application_version,financial_version,
                    definition_id,process_key,definition_version,rule_version,mode,submitted_at,snapshot_json)
                SELECT e.tenant_id,e.id,?,a.id,a.version,e.version,d.id,d.process_key,d.version,?,?,?,?
                FROM expense_report e JOIN approval_application a ON a.tenant_id=e.tenant_id AND a.id=e.application_id
                JOIN approval_definition d ON d.tenant_id=a.tenant_id AND d.process_key=a.process_key AND d.version=a.definition_version
                WHERE e.tenant_id=? AND e.id=? AND a.id=? AND e.version=? AND a.version=? AND e.current_round_no=?
                    AND a.status=? AND a.status IN ('DRAFT','RETURNED','WITHDRAWN')
                    AND (CASE WHEN a.status='DRAFT' THEN a.round_no ELSE a.round_no+1 END)=?
                    AND d.id=? AND d.process_key=? AND d.version=? AND d.status='PUBLISHED'
                """, snapshot.roundNo(), snapshot.ruleVersion(), snapshot.configuration().mode().name(),
                Timestamp.from(primary.submittedAt().truncatedTo(ChronoUnit.MICROS)), json.write(snapshot), snapshot.tenantId(), snapshot.reportId().toString(),
                snapshot.applicationId().toString(), primary.financialVersion(), primary.applicationVersion(), snapshot.roundNo(), primary.status().name(),
                snapshot.roundNo(), snapshot.definitionId().toString(), snapshot.processKey(), snapshot.definitionVersion());
        if (inserted != 1) throw new DomainException("CONCURRENCY_CONFLICT", "Split routing preparation requires the original editable application, financial revision and published definition");
        for (int ordinal = 1; ordinal < snapshot.sources().size(); ordinal++) {
            var document = snapshot.sources().get(ordinal);
            jdbc.update("""
                    INSERT INTO expense_split_routing_source(tenant_id,report_id,round_no,ordinal,source_report_id,source_application_id,
                        source_application_version,source_financial_version,source_round_no,document_json) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, snapshot.tenantId(), snapshot.reportId().toString(), snapshot.roundNo(), ordinal, document.reportId().toString(), document.applicationId().toString(),
                    document.applicationVersion(), document.financialVersion(), document.roundNo(), json.write(document));
        }
        // 用原财务修订校验完整来源；新主单轮次将在同一事务的引擎启动之后追加。
        if (!find(snapshot.tenantId(), snapshot.reportId(), snapshot.roundNo()).filter(snapshot::equals).isPresent()) throw inconsistent();
    }

    /** 只读取指定原轮次，不因后续核减或来源撤回改写历史结论。 */
    public Optional<ExpenseSplitRoutingSnapshot> find(String tenantId, UUID reportId, int roundNo) {
        return jdbc.query(SNAPSHOT_QUERY + " WHERE r.tenant_id=? AND r.report_id=? AND r.round_no=?", this::restore,
                tenantId, reportId.toString(), roundNo).stream().findFirst();
    }

    /** 引擎启动只使用同一申请和预期轮次已准备的服务端依据。 */
    public Optional<ExpenseSplitRoutingSnapshot> findByApplication(String tenantId, UUID applicationId, int roundNo) {
        return jdbc.query(SNAPSHOT_QUERY + " WHERE r.tenant_id=? AND r.application_id=? AND r.round_no=?", this::restore,
                tenantId, applicationId.toString(), roundNo).stream().findFirst();
    }

    private ExpenseSplitRoutingSnapshot restore(ResultSet row, int index) throws SQLException {
        var snapshot = json.read(row.getString("snapshot_json"), ExpenseSplitRoutingSnapshot.class);
        var primary = snapshot.primary();
        if (!snapshot.tenantId().equals(row.getString("tenant_id")) || !snapshot.reportId().toString().equals(row.getString("report_id"))
                || !snapshot.applicationId().toString().equals(row.getString("application_id")) || snapshot.roundNo() != row.getInt("round_no")
                || primary.applicationVersion() != row.getLong("application_version") || primary.financialVersion() != row.getLong("financial_version")
                || !snapshot.definitionId().toString().equals(row.getString("definition_id")) || !snapshot.processKey().equals(row.getString("process_key"))
                || snapshot.definitionVersion() != row.getLong("definition_version") || snapshot.ruleVersion() != row.getInt("rule_version")
                || !snapshot.configuration().mode().name().equals(row.getString("mode"))
                || !primary.submittedAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("submitted_at").toInstant())) throw inconsistent();
        requireFinancialState(primary, row.getString("financial_state_json"));
        var sources = jdbc.query("""
                SELECT s.*,v.state_json AS financial_state_json FROM expense_split_routing_source s
                JOIN expense_report_revision v ON v.tenant_id=s.tenant_id AND v.report_id=s.source_report_id AND v.financial_version=s.source_financial_version
                WHERE s.tenant_id=? AND s.report_id=? AND s.round_no=? ORDER BY s.ordinal
                """, (source, ordinal) -> restoreSource(source, ordinal + 1), snapshot.tenantId(), snapshot.reportId().toString(), snapshot.roundNo());
        if (!sources.equals(snapshot.sources().subList(1, snapshot.sources().size()))) throw inconsistent();
        return snapshot;
    }

    private Document restoreSource(ResultSet row, int ordinal) throws SQLException {
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
