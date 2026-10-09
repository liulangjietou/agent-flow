package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePartialAdjustmentRepositoryMapper;
import io.agentflow.finance.BudgetConsumptionReductionOperation;
import io.agentflow.finance.ExpenseAccrualReductionOperation;
import io.agentflow.finance.ExpenseAdjustmentFundingSource;
import io.agentflow.finance.Money;
import io.agentflow.jdbc.JdbcTimestampPrecision;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 部分调整按原报销串行保存，命令注册和每次执行修订同事务追加，回款原件不会被两个调整采用。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePartialAdjustmentRepository {
    private final ExpensePartialAdjustmentRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final ExpenseReportRepository reports;
    private final ExpensePartialAdjustmentSources sources;
    private final ExpensePartialAdjustmentGuard guard;
    private final JdbcExpensePartialDisputeRepository disputes;

    /** 源事实核对与互斥沿用实际业务仓储，网络调用不进入本仓储。 */
    public JdbcExpensePartialAdjustmentRepository(
            ExpensePartialAdjustmentRepositoryMapper sqlMapper,
            JsonUtil json,
            ExpenseReportRepository reports,
            ExpensePartialAdjustmentSources sources,
            ExpensePartialAdjustmentGuard guard,
            JdbcExpensePartialDisputeRepository disputes) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.reports = reports;
        this.sources = sources;
        this.guard = guard;
        this.disputes = disputes;
    }

    /** 新建只接受无外部操作的初态；前次依据从真正完成的当前记录恢复，不能采用客户端准备。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePartialAdjustment value) {
        var input = value.input();
        var basis = input.basis();
        var financial = basis.funding().financial();
        guard.requirePartialAllowed(basis.tenantId(), basis.reportId());
        if (!value.equals(ExpensePartialAdjustment.begin(input))) throw conflict();
        if (active(basis.tenantId(), basis.reportId()).isPresent()) throw pending();
        sources.requireCurrent(basis.funding());
        requireCompletedHistoryCurrent(basis);
        var previous = latestCompleted(basis.tenantId(), basis.reportId()).orElse(null);
        if (!basis.equals(ExpensePartialAdjustmentBasis.from(basis.funding(), previous)))
            throw conflict();
        requireUsedReturns(basis);
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                basis.tenantId(),
                value.id().toString(),
                basis.reportId().toString(),
                financial.settlement().input().source().roundNo(),
                sequence(basis),
                financial.settlement().version(),
                financial.consumption().input().command().id().toString(),
                financial.consumption().version(),
                financial.accrual().input().command().id().toString(),
                financial.accrual().version(),
                basis.previous() == null ? null : basis.previous().id().toString(),
                basis.previous() == null ? null : basis.previous().version(),
                json.write(input),
                json.write(value),
                basis.reportId().toString(),
                timestamp(input.createdAt()),
                timestamp(value.updatedAt()));
        append(value);
        for (var entry : basis.funding().selectedReturns())
            sqlMapper.create2(
                    basis.tenantId(),
                    value.id().toString(),
                    basis.reportId().toString(),
                    entry.proof().fundsIdentity(),
                    entry.registrationId().toString(),
                    json.write(entry),
                    entry.proof().fundsIdentity());
    }

    /** 回放一项领域转换再落库；普通状态更新不能写入资源完成标记，也不能替换原完成事实。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePartialAdjustment value) {
        var basis = value.input().basis();
        reports.lock(basis.tenantId(), basis.reportId());
        var before =
                find(basis.tenantId(), value.id())
                        .orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (!before.input().equals(value.input())
                || before.version() == Long.MAX_VALUE
                || value.version() != before.version() + 1
                || before.resolutionCount() != value.resolutionCount()
                || !Objects.equals(before.completion(), value.completion())) throw conflict();
        ExpensePartialAdjustment expected;
        ExpenseAdjustmentFundingSource authorizationSource = null;
        String newSide = null;
        if (value.retirement() != null && before.retirement() == null) {
            var decision = value.retirement();
            sources.current(basis.funding());
            expected =
                    before.retire(
                            decision.actor(),
                            decision.evidenceReference(),
                            decision.reason(),
                            decision.at());
        } else if (!Objects.equals(before.budget(), value.budget())) {
            if (value.budget() != null
                    && (before.budget() == null
                            || !before.budget().input().equals(value.budget().input()))) {
                requireCurrentPredecessor(basis);
                authorizationSource = sources.current(basis.funding());
                var source = authorizationSource.financial().consumption();
                var operation = value.budget();
                if (operation.input().consumedVersion() != source.version()
                        || !operation.input().command().source().equals(source.input().command())
                        || !operation.input().command().consumed().equals(source.observation()))
                    throw conflict();
                authorizationSource.requireAuthorization(
                        operation.input().command().authorizedBy(),
                        operation.input().command().createdAt());
                expected = before.authorizeBudget(operation, value.updatedAt());
                newSide = "BUDGET";
            } else {
                if (value.budget() != null
                        && value.budget().status()
                                == BudgetConsumptionReductionOperation.Status.EXECUTING) {
                    requireCurrentPredecessor(basis);
                    sources.current(basis.funding());
                }
                expected = before.withBudget(value.budget(), value.updatedAt());
            }
        } else if (!Objects.equals(before.accrual(), value.accrual())) {
            if (value.accrual() != null
                    && (before.accrual() == null
                            || !before.accrual().input().equals(value.accrual().input()))) {
                requireCurrentPredecessor(basis);
                authorizationSource = sources.current(basis.funding());
                var source = authorizationSource.financial().accrual();
                var operation = value.accrual();
                if (operation.input().originalVersion() != source.version()
                        || !operation
                                .input()
                                .command()
                                .source()
                                .command()
                                .equals(source.input().command())
                        || !operation
                                .input()
                                .command()
                                .source()
                                .original()
                                .equals(source.observation())) throw conflict();
                authorizationSource.requireAuthorization(
                        operation.input().command().authorizedBy(),
                        operation.input().command().createdAt());
                expected = before.authorizeAccrual(operation, value.updatedAt());
                newSide = "ACCRUAL";
            } else {
                if (value.accrual() != null
                        && value.accrual().status()
                                == ExpenseAccrualReductionOperation.Status.POSTING) {
                    requireCurrentPredecessor(basis);
                    sources.current(basis.funding());
                }
                expected = before.withAccrual(value.accrual(), value.updatedAt());
            }
        } else if (value.issue() != null)
            expected = before.requireReview(value.issue(), value.updatedAt());
        else {
            confirmationSource(before);
            expected = before.confirmCurrent(value.updatedAt());
        }
        if (!expected.equals(value)) throw conflict();
        save(value);
        if (newSide != null) registerOperation(value, newSide, authorizationSource);
        if (value.retirement() != null && before.retirement() == null) {
            int released =
                    sqlMapper.update(
                            timestamp(value.retirement().at()),
                            basis.tenantId(),
                            value.id().toString());
            if (released != basis.funding().selectedReturns().size()) throw conflict();
        }
    }

    /** 财务裁决只保存原号终态与具名证明；当前来源或历史前次变化仍由后续确认和新效果入口复核。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment resolve(ExpensePartialDisputeResolution decision) {
        var initial = find(decision.tenantId(), decision.adjustmentId()).orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        reports.lock(decision.tenantId(), initial.input().basis().reportId());
        var before = find(decision.tenantId(), decision.adjustmentId()).orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (before.version() != decision.beforeVersion()) throw conflict();
        var after = disputes.resolve(decision, before); save(after); disputes.record(decision, before, after); return after;
    }

    /** 资源执行前在原报销锁内复核全部完成历史和当前来源；查询恢复不受此新效果守卫限制。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseAdjustmentFundingSource requireCompletionSource(ExpensePartialAdjustment value) {
        var basis = value.input().basis(); reports.lock(basis.tenantId(), basis.reportId());
        if (value.status() != ExpensePartialAdjustment.Status.READY || !find(basis.tenantId(), value.id()).filter(value::equals).isPresent()) throw conflict();
        requireCurrentPredecessor(basis); requireUsedReturns(basis);
        return sources.current(basis.funding());
    }

    /** 只有本次全部资源差额实际持久后才能保存完成、回款消费和活动位置释放，任一步失败全部回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeResources(ExpensePartialAdjustment value) {
        var basis = value.input().basis();
        reports.lock(basis.tenantId(), basis.reportId());
        var before =
                find(basis.tenantId(), value.id())
                        .orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (!before.completeResources(value.updatedAt()).equals(value)) throw conflict();
        var currentSource = requireCompletionSource(before);
        var required =
                new ExpenseResourceReduction()
                        .requirements(basis.funding().financial().change()).stream()
                                .map(ExpenseResourceReduction.Requirement::effect)
                                .toList();
        var actual =
                SqlRows.map(
                        sqlMapper.completeResources(basis.tenantId(), value.id().toString()),
                        row -> {
                            if (!JdbcTimestampPrecision.matches(
                                    value.updatedAt(), row.getTimestamp("adjusted_at")))
                                throw conflict();
                            return new ExpenseResourceReversal.Consumption(
                                    ExpenseResourceReversal.Kind.valueOf(
                                            row.getString("resource_type")),
                                    UUID.fromString(row.getString("resource_id")),
                                    row.getInt("source_line"),
                                    new ExpenseUse(
                                            UUID.fromString(row.getString("report_id")),
                                            row.getInt("round_no"),
                                            row.getInt("report_line")),
                                    row.getBigDecimal("amount") == null
                                            ? null
                                            : new Money(
                                                    row.getBigDecimal("amount"),
                                                    row.getString("currency")));
                        });
        if (actual.size() != required.size()
                || !new HashSet<>(actual).equals(new HashSet<>(required))) throw conflict();
        var selected =
                SqlRows.map(
                        sqlMapper.completeResources2(basis.tenantId(), value.id().toString()),
                        row -> {
                            var entry =
                                    json.read(
                                            row.getString("entry_json"),
                                            ExpensePaymentReturns.Entry.class);
                            if (!basis.reportId().toString().equals(row.getString("report_id"))
                                    || !entry.registrationId()
                                            .toString()
                                            .equals(row.getString("registration_id"))
                                    || !entry.proof()
                                            .fundsIdentity()
                                            .equals(row.getString("funds_identity"))
                                    || !entry.proof()
                                            .fundsIdentity()
                                            .equals(row.getString("active_funds_identity"))
                                    || row.getTimestamp("completed_at") != null
                                    || row.getTimestamp("released_at") != null) throw conflict();
                            return entry;
                        });
        if (selected.size() != basis.funding().selectedReturns().size()
                || !new HashSet<>(selected)
                        .equals(new HashSet<>(basis.funding().selectedReturns()))) throw conflict();
        save(value);
        sqlMapper.completeResources3(
                basis.tenantId(),
                value.id().toString(),
                before.version(),
                value.version(),
                json.write(currentSource),
                timestamp(value.updatedAt()));
        int consumed =
                sqlMapper.completeResources4(
                        timestamp(value.updatedAt()),
                        value.version(),
                        basis.tenantId(),
                        value.id().toString());
        if (consumed != selected.size()) throw conflict();
    }

    /** 读取始终从独立租户列定位，JSON 身份及规范化操作列不一致时拒绝恢复。 */
    public Optional<ExpensePartialAdjustment> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 只有安全结束或真正资源完成才释放当前办理位置，历史原件保护仍保留。 */
    public Optional<ExpensePartialAdjustment> active(String tenant, UUID report) {
        return SqlRows.map(sqlMapper.active(tenant, report.toString()), row()).stream().findFirst();
    }

    /** 后继读取最新完成顺序，当前争议记录也返回给领域核对，不能跳过它采用更早净额。 */
    public Optional<ExpensePartialAdjustment> latestCompleted(String tenant, UUID report) {
        return SqlRows.map(sqlMapper.latestCompleted(tenant, report.toString()), row()).stream()
                .findFirst();
    }

    /** 按实际办理顺序展示全部历史，复核和安全结束不会从页面消失。 */
    public List<ExpensePartialAdjustment> history(String tenant, UUID report) {
        return SqlRows.map(sqlMapper.history(tenant, report.toString()), row());
    }

    /** 活动占用和已完成消费都排除再选，只有持久安全结束才释放原入款。 */
    public java.util.Set<String> claimedReturnIds(String tenant, UUID report) {
        return java.util.Set.copyOf(sqlMapper.claimedReturnIds(tenant, report.toString()));
    }

    /** 精确修订作为后继和授权证据，不允许原号对应另一份状态。 */
    public Optional<ExpensePartialAdjustment> revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            ExpensePartialAdjustment.class);
                            if (!value.input().basis().tenantId().equals(tenant)
                                    || !value.id().equals(id)
                                    || value.version() != version) throw inconsistent();
                            requireRegisteredOperations(value);
                            requireRecordedCompletion(value);
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 预算和会计分别扫描各自到期队列，同一操作租约到期后仍只查询原号。 */
    public List<Candidate> dueBudget(Instant at) { return due("budget", at); }

    /** 不以预算已经成功推断 ERP 已过账，单独领取会计操作。 */
    public List<Candidate> dueAccrual(Instant at) { return due("accrual", at); }

    /** 两侧成功后独立扫描本地完成，数据库异常或重启不会再次发送已成功命令。 */
    public List<Candidate> ready() {
        return SqlRows.map(
                sqlMapper.ready(),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                UUID.fromString(row.getString("report_id")),
                                row.getLong("version"),
                                row.getString("trace_id"),
                                null,
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    /** 调用方持有原报销锁；只读拒绝不标记事务回滚，使应用服务能够原子停止失效队列。 */
    public ExpenseAdjustmentFundingSource dispatchSource(ExpensePartialAdjustment value) {
        if (value.issue() != null || value.completion() != null || value.retirement() != null) throw conflict();
        requireCurrentPredecessor(value.input().basis());
        return sources.current(value.input().basis().funding());
    }

    /** 未完成调整的恢复仍受前次完成约束；历史完成只确认原事实，不要求它仍是最新一笔。 */
    public ExpenseAdjustmentFundingSource confirmationSource(ExpensePartialAdjustment value) {
        if (value.completion() == null) requireCurrentPredecessor(value.input().basis());
        return sources.current(value.input().basis().funding());
    }

    private List<Candidate> due(String side, Instant at) {
        // 预算与会计分别授权，后台来源必须读取当前指令的登记记录，不能沿用调整创建请求。
        return SqlRows.map(
                sqlMapper.dueQuery(side, new Object[] {timestamp(at), timestamp(at)}),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                UUID.fromString(row.getString("report_id")),
                                row.getLong("version"),
                                row.getString("trace_id"),
                                UUID.fromString(row.getString("operation_id")),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private void save(ExpensePartialAdjustment value) {
        var basis = value.input().basis();
        var budget = value.budget();
        var accrual = value.accrual();
        int changed =
                sqlMapper.save(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        activeReport(value),
                        budget == null ? null : budget.input().command().id().toString(),
                        budget == null ? null : budget.status().name(),
                        budget == null ? null : timestamp(budget.nextAttemptAt()),
                        budget == null ? null : timestamp(budget.leaseUntil()),
                        accrual == null ? null : accrual.input().command().id().toString(),
                        accrual == null ? null : accrual.status().name(),
                        accrual == null ? null : timestamp(accrual.nextAttemptAt()),
                        accrual == null ? null : timestamp(accrual.leaseUntil()),
                        value.completion() == null ? null : timestamp(value.completion().at()),
                        value.completion() == null ? null : sequence(basis),
                        value.retirement() == null ? null : timestamp(value.retirement().at()),
                        timestamp(value.updatedAt()),
                        value.resolutionCount(),
                        basis.tenantId(),
                        value.id().toString(),
                        value.version() - 1,
                        json.write(value.input()));
        if (changed != 1) throw conflict();
        append(value);
    }

    private void registerOperation(
            ExpensePartialAdjustment value, String side, ExpenseAdjustmentFundingSource source) {
        boolean budget = side.equals("BUDGET");
        var id =
                budget
                        ? value.budget().input().command().id()
                        : value.accrual().input().command().id();
        Object input = budget ? value.budget().input() : value.accrual().input();
        sqlMapper.registerOperation(
                DiagnosticContext.capture().traceId(),
                value.input().basis().tenantId(),
                id.toString(),
                value.id().toString(),
                side,
                value.version(),
                json.write(input),
                json.write(source),
                timestamp(value.updatedAt()));
    }

    private void requireUsedReturns(ExpensePartialAdjustmentBasis basis) {
        for (var entry : basis.funding().previousReturns()) {
            var known =
                    sqlMapper.requireUsedReturns(
                            basis.tenantId(),
                            basis.reportId().toString(),
                            entry.proof().fundsIdentity());
            if (known.size() != 1
                    || !entry.equals(json.read(known.get(0), ExpensePaymentReturns.Entry.class)))
                throw conflict();
        }
    }

    /** 新效果不能跨过前次争议；完成后无变化的查询允许增加修订，但原完成事实不能替换。 */
    private void requireCurrentPredecessor(ExpensePartialAdjustmentBasis basis) {
        requireCompletedHistoryCurrent(basis);
        var current = latestCompleted(basis.tenantId(), basis.reportId()).orElse(null);
        var expected = basis.previous();
        if (expected == null) { if (current != null) throw conflict(); return; }
        if (current == null || !current.id().equals(expected.id()) || current.version() < expected.version()
                || current.status() != ExpensePartialAdjustment.Status.APPLIED) throw conflict();
        var original = revision(basis.tenantId(), expected.id(), expected.version()).orElseThrow(JdbcExpensePartialAdjustmentRepository::conflict);
        if (!basis.equals(ExpensePartialAdjustmentBasis.from(basis.funding(), original)) || !current.input().equals(original.input())
                || !current.completion().equals(original.completion())) throw conflict();
    }

    private void requireCompletedHistoryCurrent(ExpensePartialAdjustmentBasis basis) {
        if (!sqlMapper
                .requireCompletedHistoryCurrent(basis.tenantId(), basis.reportId().toString())
                .isEmpty()) throw conflict();
    }

    private long sequence(ExpensePartialAdjustmentBasis basis) {
        if (basis.previous() == null) return 1;
        var previous =
                sqlMapper.sequence(
                        basis.tenantId(),
                        basis.previous().id().toString(),
                        basis.reportId().toString());
        if (previous.size() != 1) throw conflict();
        return Math.incrementExact(previous.get(0));
    }

    private Function<SqlRow, ExpensePartialAdjustment> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), ExpensePartialAdjustment.class);
            var basis = value.input().basis();
            var financial = basis.funding().financial();
            var budget = value.budget();
            var accrual = value.accrual();
            if (!basis.tenantId().equals(row.getString("tenant_id"))
                    || !value.id().toString().equals(row.getString("id"))
                    || !basis.reportId().toString().equals(row.getString("report_id"))
                    || financial.settlement().input().source().roundNo() != row.getInt("round_no")
                    || row.getLong("sequence_no") != sequence(basis)
                    || financial.settlement().version() != row.getLong("settlement_version")
                    || financial.consumption().version() != row.getLong("consumed_version")
                    || !financial
                            .consumption()
                            .input()
                            .command()
                            .id()
                            .toString()
                            .equals(row.getString("consumption_id"))
                    || financial.accrual().version() != row.getLong("accrual_version")
                    || !financial
                            .accrual()
                            .input()
                            .command()
                            .id()
                            .toString()
                            .equals(row.getString("accrual_id"))
                    || !Objects.equals(
                            basis.previous() == null ? null : basis.previous().id().toString(),
                            row.getString("previous_id"))
                    || !Objects.equals(
                            basis.previous() == null ? null : basis.previous().version(),
                            row.getObject("previous_version", Long.class))
                    || !value.input()
                            .equals(
                                    json.read(
                                            row.getString("input_json"),
                                            ExpensePartialAdjustment.Input.class))
                    || value.version() != row.getLong("version")
                    || value.resolutionCount() != row.getInt("resolution_count")
                    || !value.status().name().equals(row.getString("status"))
                    || !Objects.equals(activeReport(value), row.getString("active_report_id"))
                    || !Objects.equals(
                            budget == null ? null : budget.input().command().id().toString(),
                            row.getString("budget_operation_id"))
                    || !Objects.equals(
                            budget == null ? null : budget.status().name(),
                            row.getString("budget_status"))
                    || !JdbcTimestampPrecision.matches(
                            budget == null ? null : budget.nextAttemptAt(),
                            row.getTimestamp("budget_next_at"))
                    || !JdbcTimestampPrecision.matches(
                            budget == null ? null : budget.leaseUntil(),
                            row.getTimestamp("budget_lease_until"))
                    || !Objects.equals(
                            accrual == null ? null : accrual.input().command().id().toString(),
                            row.getString("accrual_operation_id"))
                    || !Objects.equals(
                            accrual == null ? null : accrual.status().name(),
                            row.getString("accrual_status"))
                    || !JdbcTimestampPrecision.matches(
                            accrual == null ? null : accrual.nextAttemptAt(),
                            row.getTimestamp("accrual_next_at"))
                    || !JdbcTimestampPrecision.matches(
                            accrual == null ? null : accrual.leaseUntil(),
                            row.getTimestamp("accrual_lease_until"))
                    || !JdbcTimestampPrecision.matches(
                            value.completion() == null ? null : value.completion().at(),
                            row.getTimestamp("completed_at"))
                    || !Objects.equals(
                            value.completion() == null ? null : sequence(basis),
                            row.getObject("completed_sequence", Long.class))
                    || !JdbcTimestampPrecision.matches(
                            value.retirement() == null ? null : value.retirement().at(),
                            row.getTimestamp("retired_at"))
                    || !JdbcTimestampPrecision.matches(
                            value.input().createdAt(), row.getTimestamp("created_at"))
                    || !JdbcTimestampPrecision.matches(
                            value.updatedAt(), row.getTimestamp("updated_at")))
                throw inconsistent();
            requireRegisteredOperations(value);
            requireRecordedCompletion(value);
            disputes.recorded(value);
            return value;
        };
    }

    /** 历史完成引用真实相邻修订，后续查询不能换掉原接受凭据，也不能仅凭 JSON 标记完成。 */
    private void requireRecordedCompletion(ExpensePartialAdjustment value) {
        if (value.completion() == null) return;
        var proof =
                SqlRows.map(
                        sqlMapper.requireRecordedCompletion(
                                value.input().basis().tenantId(), value.id().toString()),
                        row -> {
                            var before =
                                    json.read(
                                            row.getString("before_json"),
                                            ExpensePartialAdjustment.class);
                            var after =
                                    json.read(
                                            row.getString("after_json"),
                                            ExpensePartialAdjustment.class);
                            if (before.version() != row.getLong("before_version")
                                    || after.version() != row.getLong("after_version")
                                    || after.version() > value.version()
                                    || !before.completeResources(
                                                    instant(row.getTimestamp("completed_at")))
                                            .equals(after)
                                    || !after.input().equals(value.input())
                                    || !after.completion().equals(value.completion()))
                                throw inconsistent();
                            ExpensePartialAdjustmentSources.requireContinuation(
                                    value.input().basis().funding(),
                                    json.read(
                                            row.getString("source_json"),
                                            ExpenseAdjustmentFundingSource.class));
                            return true;
                        });
        if (proof.size() != 1) throw inconsistent();
    }

    private void requireRegisteredOperations(ExpensePartialAdjustment value) {
        if (value.budget() != null) requireRegistered(value, "BUDGET", value.budget().input().command().id(), value.budget().input());
        if (value.accrual() != null) requireRegistered(value, "ACCRUAL", value.accrual().input().command().id(), value.accrual().input());
    }

    private void requireRegistered(
            ExpensePartialAdjustment value, String side, UUID id, Object input) {
        var registered =
                SqlRows.map(
                        sqlMapper.requireRegistered(
                                value.input().basis().tenantId(),
                                id.toString(),
                                value.id().toString(),
                                side),
                        row -> {
                            if (row.getLong("adjustment_version") > value.version()
                                    || !json.write(input).equals(row.getString("input_json")))
                                throw inconsistent();
                            var source =
                                    json.read(
                                            row.getString("authorization_source_json"),
                                            ExpenseAdjustmentFundingSource.class);
                            var basis =
                                    new ExpensePartialAdjustmentBasis(
                                            source, value.input().basis().previous());
                            if (side.equals("BUDGET"))
                                basis.requireBudget(value.id(), value.budget().input());
                            else basis.requireAccrual(value.id(), value.accrual().input());
                            return true;
                        });
        if (registered.size() != 1) throw inconsistent();
    }

    private void append(ExpensePartialAdjustment value) {
        sqlMapper.append(
                value.input().basis().tenantId(),
                value.id().toString(),
                value.version(),
                json.write(value));
    }

    private static String activeReport(ExpensePartialAdjustment value) { return value.completion() == null && value.retirement() == null ? value.input().basis().reportId().toString() : null; }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(JdbcTimestampPrecision.roundedToMicros(value)); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static DomainException pending() { return new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_PENDING", "Another partial adjustment already protects this expense"); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial adjustment source, current operation or version changed"); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted partial adjustment identity or command registration is inconsistent"); }

    /**
     * 工作器只传租户、报销和当前修订，账务内容由实际状态恢复。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId,
            UUID id,
            UUID reportId,
            long version,
            String traceId,
            UUID operationId,
            String businessNo,
            String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public Candidate(String tenantId, UUID id, UUID reportId, long version, String traceId, UUID operationId) { this(tenantId, id, reportId, version, traceId, operationId, null, null); }

        /** 本地资源完成没有外部指令身份。 */
        public Candidate(String tenantId, UUID id, UUID reportId, long version, String traceId) { this(tenantId, id, reportId, version, traceId, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id, UUID reportId, long version) { this(tenantId, id, reportId, version, null, null); }
    }
}
