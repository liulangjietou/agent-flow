package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.mapper.ExpenseResourceAdjustmentPreparationRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 只读准备引用原结算及各财务修订，授权后的输入不能被重写或重复消费。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcExpenseResourceAdjustmentPreparationRepository {
    private final ExpenseResourceAdjustmentPreparationRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final ExpenseResourceAdjustmentSources sources;
    private final ExpensePartialAdjustmentGuard partialAdjustments;

    /** 完整快照与规范化原修订引用在同一个事务登记。 */
    public JdbcExpenseResourceAdjustmentPreparationRepository(
            ExpenseResourceAdjustmentPreparationRepositoryMapper sqlMapper,
            JsonUtil json,
            ExpenseResourceAdjustmentSources sources,
            ExpensePartialAdjustmentGuard partialAdjustments) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.sources = sources;
        this.partialAdjustments = partialAdjustments;
    }

    /** 新建只能是无期间、无命令的排队意图，来源内容必须等于实际原记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ExpenseResourceAdjustmentPreparation value) {
        if (!value.equals(ExpenseResourceAdjustmentPreparation.queue(value.input())))
            throw conflict();
        var input = value.input();
        var basis = input.basis();
        partialAdjustments.requireWholeAllowed(basis.tenantId(), basis.reportId());
        sources.requireCurrent(basis);
        var payment = basis.paymentVoucher();
        sqlMapper.create(
                DiagnosticContext.capture().traceId(),
                basis.tenantId(),
                input.id().toString(),
                basis.reportId().toString(),
                basis.settlement().version(),
                basis.consumption().input().command().id().toString(),
                basis.consumption().version(),
                basis.accrualReversal().id().toString(),
                basis.paymentReturns() == null ? null : basis.paymentReturns().version(),
                payment == null ? null : payment.input().command().id().toString(),
                payment == null ? null : payment.version(),
                basis.paymentVoucherReversal() == null
                        ? null
                        : basis.paymentVoucherReversal().id().toString(),
                input.requestedBy(),
                json.write(input),
                json.write(value),
                timestamp(input.requestedAt()),
                timestamp(value.updatedAt()));
        append(value);
    }

    /** 租约完成或授权均按原输入和相邻版本更新，过期执行者不能覆盖新状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(ExpenseResourceAdjustmentPreparation value) {
        var input = value.input();
        int changed =
                sqlMapper.update(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        timestamp(value.updatedAt()),
                        timestamp(value.leaseUntil()),
                        input.basis().tenantId(),
                        input.id().toString(),
                        value.version() - 1,
                        json.write(input));
        if (changed != 1) throw conflict();
        append(value);
    }

    public Optional<ExpenseResourceAdjustmentPreparation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 明确授权只消费本人最新的准备，其他人员的候选不替换这次意图。 */
    public Optional<ExpenseResourceAdjustmentPreparation> latest(
            String tenant, UUID report, String actor) {
        return SqlRows.map(sqlMapper.latest(tenant, report.toString(), actor), row()).stream()
                .findFirst();
    }

    /** 已授权修订作为预算写入的不可变来源，不依赖随后读取的当前时间。 */
    public Optional<ExpenseResourceAdjustmentPreparation> revision(
            String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            ExpenseResourceAdjustmentPreparation.class);
                            if (!value.input().basis().tenantId().equals(tenant)
                                    || !value.input().id().equals(id)
                                    || value.version() != version) throw inconsistent();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 到期读取有界扫描，运行中任务只在租约过期时恢复。 */
    public List<Candidate> due(Instant at) {
        // 准备登记时已固定原结算及消费编号；结算输入不可替换，不沿当前申请轮次查询。
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

    private Function<SqlRow, ExpenseResourceAdjustmentPreparation> row() {
        return row -> {
            var value =
                    json.read(
                            row.getString("state_json"),
                            ExpenseResourceAdjustmentPreparation.class);
            var input = value.input();
            var basis = input.basis();
            var payment = basis.paymentVoucher();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    ExpenseResourceAdjustmentPreparation.Input.class))
                    || !basis.tenantId().equals(row.getString("tenant_id"))
                    || !input.id().toString().equals(row.getString("id"))
                    || !basis.reportId().toString().equals(row.getString("report_id"))
                    || basis.settlement().version() != row.getLong("settlement_version")
                    || !basis.consumption()
                            .input()
                            .command()
                            .id()
                            .toString()
                            .equals(row.getString("consumption_id"))
                    || basis.consumption().version() != row.getLong("consumed_version")
                    || !basis.accrualReversal()
                            .id()
                            .toString()
                            .equals(row.getString("accrual_reversal_id"))
                    || !Objects.equals(
                            basis.paymentReturns() == null
                                    ? null
                                    : basis.paymentReturns().version(),
                            row.getObject("payment_returns_version", Long.class))
                    || !Objects.equals(
                            payment == null ? null : payment.input().command().id().toString(),
                            row.getString("payment_voucher_id"))
                    || !Objects.equals(
                            payment == null ? null : payment.version(),
                            row.getObject("payment_voucher_version", Long.class))
                    || !Objects.equals(
                            basis.paymentVoucherReversal() == null
                                    ? null
                                    : basis.paymentVoucherReversal().id().toString(),
                            row.getString("payment_voucher_reversal_id"))
                    || !input.requestedBy().equals(row.getString("requested_by"))
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || !input.requestedAt().equals(row.getTimestamp("created_at").toInstant())
                    || !value.updatedAt().equals(row.getTimestamp("updated_at").toInstant())
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until"))))
                throw inconsistent();
            return value;
        };
    }

    private void append(ExpenseResourceAdjustmentPreparation value) {
        sqlMapper.append(
                value.input().basis().tenantId(),
                value.input().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }

    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense adjustment preparation input or version changed"); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted resource adjustment preparation identity is inconsistent"); }

    /**
     * 调度索引不携带金额或原件正文。
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
