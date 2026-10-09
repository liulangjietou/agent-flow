package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpensePartialPreparationRepositoryMapper;
import io.agentflow.jdbc.JdbcTimestampPrecision;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 单侧准备及消费关联实际前后修订，不能通过普通更新伪造授权或重复生成操作编号。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpensePartialPreparationRepository {
    private final ExpensePartialPreparationRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final ExpenseReportRepository reports;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final ExpensePartialAdjustmentSources sources;

    /** 原报销锁与实际调整仓储保护不同财务、不同侧的并发确认。 */
    public JdbcExpensePartialPreparationRepository(
            ExpensePartialPreparationRepositoryMapper sqlMapper,
            JsonUtil json,
            ExpenseReportRepository reports,
            JdbcExpensePartialAdjustmentRepository adjustments,
            ExpensePartialAdjustmentSources sources) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.reports = reports;
        this.adjustments = adjustments;
        this.sources = sources;
    }

    /** 只接受真实调整当前修订派生的初始意图，不能直接插入就绪或授权状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpensePartialAdjustmentPreparation value) {
        var input = value.input();
        var original = input.adjustment();
        var basis = original.input().basis();
        reports.lock(basis.tenantId(), basis.reportId());
        if (!value.equals(ExpensePartialAdjustmentPreparation.queue(input))
                || !adjustments
                        .find(basis.tenantId(), original.id())
                        .filter(original::equals)
                        .isPresent()) throw conflict();
        adjustments.dispatchSource(original);
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                basis.tenantId(),
                input.id().toString(),
                original.id().toString(),
                original.version(),
                basis.reportId().toString(),
                input.side().name(),
                input.requestedBy(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 按领域事件重放单步转换；实际财务观察必须已经通过原账本登记。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpensePartialAdjustmentPreparation value) {
        var before = locked(value); var at = value.updatedAt();
        var expected = switch (value.status()) {
            case RUNNING -> before.claim(value.readSource(), at, Duration.between(at, value.leaseUntil()));
            case READY -> before.ready(value.evidence(), at);
            case UNAVAILABLE -> before.fail(value.issue(), at);
            case VOIDED -> before.voidSource(at);
            default -> throw conflict();
        };
        if (!expected.equals(value)) throw conflict();
        if (value.status() == ExpensePartialAdjustmentPreparation.Status.RUNNING) sources.requireCurrent(value.readSource());
        if (value.status() == ExpensePartialAdjustmentPreparation.Status.READY) sources.requireCurrent(value.evidence().source());
        save(value);
    }

    /** 准备消费、所选侧授权、永久命令及相邻修订证明共同提交，任何一步失败全部回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment consume(ExpensePartialAdjustmentPreparation value) {
        var before = locked(value);
        var input = value.input();
        var basis = input.adjustment().input().basis();
        var current =
                adjustments
                        .find(basis.tenantId(), input.adjustment().id())
                        .orElseThrow(JdbcExpensePartialPreparationRepository::conflict);
        if (!before.authorize(current, value.updatedAt()).equals(value)
                || !latest(basis.tenantId(), current.id(), input.side(), input.requestedBy())
                        .filter(before::equals)
                        .isPresent()) throw conflict();
        sources.requireCurrent(value.evidence().source());
        adjustments.dispatchSource(current);
        var authorized = value.authorizedAdjustment(current);
        save(value);
        adjustments.update(authorized);
        sqlMapper.consume(
                basis.tenantId(),
                input.id().toString(),
                current.id().toString(),
                before.version(),
                value.version(),
                current.version(),
                authorized.version(),
                input.id().toString(),
                timestamp(value.updatedAt()));
        return authorized;
    }

    /** 租户、原调整和规范化版本与 JSON 同时核对，历史消费不会依赖当前财务状态。 */
    public Optional<ExpensePartialAdjustmentPreparation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 原办理人只消费所选侧最新候选，重新准备不复活旧的有效期。 */
    public Optional<ExpensePartialAdjustmentPreparation> latest(
            String tenant,
            UUID adjustment,
            ExpensePartialAdjustmentPreparation.Side side,
            String actor) {
        return SqlRows.map(
                        sqlMapper.latest(tenant, adjustment.toString(), side.name(), actor), row())
                .stream()
                .findFirst();
    }

    /** 到期租约与新排队各有确定持久身份，单批最多十笔。 */
    public List<Candidate> due(Instant at) {
        return SqlRows.map(
                sqlMapper.due(timestamp(at)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private ExpensePartialAdjustmentPreparation locked(ExpensePartialAdjustmentPreparation next) {
        var input = next.input(); var basis = input.adjustment().input().basis(); reports.lock(basis.tenantId(), basis.reportId());
        var before = find(basis.tenantId(), input.id()).orElseThrow(JdbcExpensePartialPreparationRepository::conflict);
        if (!before.input().equals(input) || before.version() == Long.MAX_VALUE || next.version() != before.version() + 1 || before.status() == ExpensePartialAdjustmentPreparation.Status.AUTHORIZED) throw conflict();
        return before;
    }

    private void save(ExpensePartialAdjustmentPreparation value) {
        var input = value.input();
        var tenant = input.adjustment().input().basis().tenantId();
        // 已在原报销锁内核对输入语义；比较实际原文，兼容旧快照缺少新增的默认字段。
        var originalInput =
                SqlRows.map(
                                sqlMapper.save(tenant, input.id().toString(), value.version() - 1),
                                row -> row.getString("input_json"))
                        .stream()
                        .findFirst()
                        .orElseThrow(JdbcExpensePartialPreparationRepository::conflict);
        int changed =
                sqlMapper.save2(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.active() ? 1 : null,
                        timestamp(value.leaseUntil()),
                        timestamp(value.updatedAt()),
                        tenant,
                        input.id().toString(),
                        value.version() - 1,
                        originalInput);
        if (changed != 1) throw conflict();
        append(value);
    }

    private Function<SqlRow, ExpensePartialAdjustmentPreparation> row() {
        return row -> {
            var value =
                    json.read(
                            row.getString("state_json"), ExpensePartialAdjustmentPreparation.class);
            var input = value.input();
            var original = input.adjustment();
            var basis = original.input().basis();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    ExpensePartialAdjustmentPreparation.Input.class))
                    || !basis.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !original.id().toString().equals(row.getString("adjustment_id"))
                    || original.version() != row.getLong("adjustment_version")
                    || !basis.reportId().toString().equals(row.getString("report_id"))
                    || !input.side().name().equals(row.getString("side"))
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || !Objects.equals(
                            value.active() ? 1 : null, row.getObject("active_slot", Integer.class))
                    || !JdbcTimestampPrecision.matches(
                            input.requestedAt(), row.getTimestamp("created_at"))
                    || !JdbcTimestampPrecision.matches(
                            value.updatedAt(), row.getTimestamp("updated_at"))
                    || !JdbcTimestampPrecision.matches(
                            value.leaseUntil(), row.getTimestamp("lease_until")))
                throw inconsistent();
            if (value.status() == ExpensePartialAdjustmentPreparation.Status.AUTHORIZED)
                requireConsumption(value);
            return value;
        };
    }

    private void requireConsumption(ExpensePartialAdjustmentPreparation value) {
        var input = value.input();
        var tenant = input.adjustment().input().basis().tenantId();
        var proofs =
                SqlRows.map(
                        sqlMapper.requireConsumption(tenant, input.id().toString()),
                        row -> {
                            if (!input.adjustment()
                                            .id()
                                            .toString()
                                            .equals(row.getString("adjustment_id"))
                                    || row.getLong("preparation_after") != value.version()
                                    || row.getLong("adjustment_after") != value.authorizedVersion()
                                    || !row.getString("operation_id").equals(input.id().toString())
                                    || !JdbcTimestampPrecision.matches(
                                            value.updatedAt(), row.getTimestamp("authorized_at")))
                                throw inconsistent();
                            var before =
                                    adjustments
                                            .revision(
                                                    tenant,
                                                    input.adjustment().id(),
                                                    row.getLong("adjustment_before"))
                                            .orElseThrow(
                                                    JdbcExpensePartialPreparationRepository
                                                            ::inconsistent);
                            var after =
                                    adjustments
                                            .revision(
                                                    tenant,
                                                    input.adjustment().id(),
                                                    row.getLong("adjustment_after"))
                                            .orElseThrow(
                                                    JdbcExpensePartialPreparationRepository
                                                            ::inconsistent);
                            var prepared =
                                    revision(tenant, input.id(), row.getLong("preparation_before"));
                            var authorized =
                                    revision(tenant, input.id(), row.getLong("preparation_after"));
                            try {
                                if (!value.equals(authorized)
                                        || !prepared.authorize(before, value.updatedAt())
                                                .equals(value)
                                        || !value.authorizedAdjustment(before).equals(after))
                                    throw inconsistent();
                            } catch (DomainException invalidHistory) {
                                throw inconsistent();
                            }
                            return true;
                        });
        if (proofs.size() != 1) throw inconsistent();
    }

    private ExpensePartialAdjustmentPreparation revision(String tenant, UUID id, long version) {
        try {
            return SqlRows.map(
                            sqlMapper.revision(tenant, id.toString(), version),
                            row -> {
                                var value =
                                        json.read(
                                                row.getString("state_json"),
                                                ExpensePartialAdjustmentPreparation.class);
                                if (value.version() != version
                                        || !value.input().id().equals(id)
                                        || !value.input()
                                                .adjustment()
                                                .input()
                                                .basis()
                                                .tenantId()
                                                .equals(tenant)) throw inconsistent();
                                return value;
                            })
                    .stream()
                    .findFirst()
                    .orElseThrow(JdbcExpensePartialPreparationRepository::inconsistent);
        } catch (DomainException invalidHistory) {
            throw inconsistent();
        }
    }

    private void append(ExpensePartialAdjustmentPreparation value) {
        sqlMapper.append(
                value.input().adjustment().input().basis().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(JdbcTimestampPrecision.roundedToMicros(at)); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial preparation or selected authorization changed"); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted partial preparation identity or authorization proof is inconsistent"); }

    /**
     * 扫描候选仅携带租户和准备编号。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 旧候选没有业务关联时保留空值，工作器不得借用调用线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }
}
