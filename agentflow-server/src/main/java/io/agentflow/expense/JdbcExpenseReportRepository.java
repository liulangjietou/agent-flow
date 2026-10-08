package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseReportRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 财务事实与申请状态分开持久化；每次修改追加完整版本，保留最初提交及每次核减证据。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseReportRepository implements ExpenseReportRepository {
    private final ExpenseReportRepositoryMapper sqlMapper;
    private final JsonUtil json;

    /** 共用平台事务管理器，跨聚合编排可以一并回滚。 */
    public JdbcExpenseReportRepository(ExpenseReportRepositoryMapper sqlMapper, JsonUtil json) {
        this.sqlMapper = sqlMapper;
        this.json = json;
    }

    /** 所有跨聚合财务编排共用相同锁顺序，不能把锁留在某一种后台任务仓储。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String tenant, UUID reportId) {
        var application = sqlMapper.lock(tenant, reportId.toString());
        if (application.isEmpty())
            throw new DomainException("NOT_FOUND", "Expense report not found");
        sqlMapper.lock2(tenant, application.get(0));
        sqlMapper.lock3(tenant, reportId.toString());
    }

    @Override
    @Transactional
    public void create(ExpenseReport report, String actor) {
        requireAudit(actor, "CREATE");
        if (report.version() != 1 || !report.rounds().isEmpty()) throw conflict();
        int inserted =
                sqlMapper.create(
                        report.id().toString(),
                        json.write(report.state()),
                        report.tenantId(),
                        report.applicationId().toString(),
                        report.employeeId(),
                        report.id().toString());
        if (inserted != 1) throw conflict();
        append(report, actor, "CREATE");
    }

    @Override
    @Transactional
    public void update(ExpenseReport report, long expectedVersion, String actor, String operation) {
        requireAudit(actor, operation);
        if (report.version() != expectedVersion + 1) throw conflict();
        var round = report.rounds().isEmpty() ? null : report.currentRound();
        int updated =
                sqlMapper.update(
                        report.version(),
                        json.write(report.state()),
                        round == null ? null : round.roundNo(),
                        round == null
                                ? null
                                : Timestamp.from(
                                        round.submittedAt().truncatedTo(ChronoUnit.MICROS)),
                        round == null ? null : round.content().legalEntityId().toString(),
                        round == null ? null : round.baseCurrency(),
                        report.tenantId(),
                        report.id().toString(),
                        report.applicationId().toString(),
                        report.employeeId(),
                        expectedVersion);
        if (updated != 1) throw conflict();
        append(report, actor, operation);
    }

    @Override
    public Optional<ExpenseReport> find(String tenantId, UUID id) {
        return SqlRows.map(sqlMapper.find(tenantId, id.toString()), this::restore).stream()
                .findFirst();
    }

    @Override
    public Optional<ExpenseReport> findByApplication(String tenantId, UUID applicationId) {
        return SqlRows.map(
                        sqlMapper.findByApplication(tenantId, applicationId.toString()),
                        this::restore)
                .stream()
                .findFirst();
    }

    private void append(ExpenseReport report, String actor, String operation) {
        sqlMapper.append(
                report.tenantId(),
                report.id().toString(),
                report.version(),
                actor,
                operation,
                json.write(report.state()));
    }

    private ExpenseReport restore(SqlRow row) {
        return restoreCurrent(row, json);
    }

    /** 当前报销查询和跨单单条 SQL 共用投影恢复校验，防止旧轮次或空投影静默漏算。 */
    static ExpenseReport restoreCurrent(SqlRow row, JsonUtil json) {
        var state = json.read(row.getString("state_json"), ExpenseReport.State.class);
        if (!state.id().toString().equals(row.getString("id")) || !state.tenantId().equals(row.getString("tenant_id"))
                || !state.applicationId().toString().equals(row.getString("application_id"))
                || !state.employeeId().equals(row.getString("employee_id")) || state.version() != row.getLong("version")) {
            throw new IllegalStateException("Persisted expense report binding is inconsistent");
        }
        var report = ExpenseReport.restore(state);
        var round = report.rounds().isEmpty() ? null : report.currentRound();
        var storedAt = row.getTimestamp("current_submitted_at");
        if (!Objects.equals(round == null ? null : round.roundNo(), row.getObject("current_round_no", Integer.class))
                || !Objects.equals(round == null ? null : round.submittedAt().truncatedTo(ChronoUnit.MICROS), storedAt == null ? null : storedAt.toInstant())
                || !Objects.equals(round == null ? null : round.content().legalEntityId().toString(), row.getString("current_legal_entity_id"))
                || !Objects.equals(round == null ? null : round.baseCurrency(), row.getString("current_base_currency"))) {
            throw new IllegalStateException("Persisted expense current round projection is inconsistent");
        }
        return report;
    }

    private static void requireAudit(String actor, String operation) {
        if (StringUtils.isBlank(actor) || actor.length() > 128 || operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException("INVALID_EXPENSE_AUDIT", "Financial changes require an actor and operation");
        }
    }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense binding or financial version has changed"); }
}
