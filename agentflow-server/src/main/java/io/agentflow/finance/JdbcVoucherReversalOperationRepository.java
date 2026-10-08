package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpensePartialAdjustmentGuard;
import io.agentflow.finance.mapper.VoucherReversalOperationRepositoryMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;

import org.springframework.dao.DuplicateKeyException;
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
 * 冲销操作与全部执行修订独立持久化，同一原凭证只允许一份未结束命令。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherReversalOperationRepository {
    private final VoucherReversalOperationRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcVoucherReversalPreparationRepository preparations;
    private final JdbcVoucherOperationRepository originals;
    private final ExpensePartialAdjustmentGuard partialAdjustments;

    /** 仓储核对被消费的准备和原修订，不信任调用方传入的过账声明。 */
    public JdbcVoucherReversalOperationRepository(
            VoucherReversalOperationRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcVoucherReversalPreparationRepository preparations,
            JdbcVoucherOperationRepository originals,
            ExpensePartialAdjustmentGuard partialAdjustments) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.preparations = preparations;
        this.originals = originals;
        this.partialAdjustments = partialAdjustments;
    }

    /** 原件冻结与命令登记由同一事务完成；同一原件的并发授权只能成功一次。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherReversalOperation value) {
        var input = value.input();
        var command = input.command();
        var original = command.source().command();
        if (original.kind() == VoucherCommand.Kind.EXPENSE_ACCRUAL)
            partialAdjustments.requireWholeAllowed(
                    original.tenantId(), original.binding().businessId());
        if (value.version() != 1
                || value.status() != VoucherReversalOperation.Status.QUEUED
                || value.attempts() != 0) throw conflict();
        var prepared =
                preparations
                        .find(original.tenantId(), command.id())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        var source =
                originals
                        .revision(original.tenantId(), original.id(), input.originalVersion())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        if (prepared.status() != VoucherReversalPreparation.Status.AUTHORIZED
                || !command.equals(prepared.command())
                || !prepared.input().targetDigest().equals(input.targetDigest())
                || prepared.input().operationVersion() != input.originalVersion()
                || !source.usablePosted()
                || !source.input().command().equals(original)
                || !source.input().targetDigest().equals(input.targetDigest())
                || !originals
                        .find(original.tenantId(), original.id())
                        .filter(source::equals)
                        .isPresent()) throw conflict();
        try {
            sqlMapper.create(
                    DiagnosticContext.capture().traceId(),
                    original.tenantId(),
                    command.id().toString(),
                    original.id().toString(),
                    input.originalVersion(),
                    prepared.version(),
                    json.write(input),
                    command.digest(),
                    json.write(value),
                    timestamp(value.createdAt()),
                    timestamp(value.updatedAt()),
                    timestamp(value.nextAttemptAt()),
                    original.id().toString());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "VOUCHER_REVERSAL_OPERATION_EXISTS",
                    "The original voucher already has an immutable reversal command");
        }
        append(value);
    }

    /** 乐观版本和固定输入保护租约，迟到结果不能覆盖新执行者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(VoucherReversalOperation value) {
        var command = value.input().command();
        int count =
                sqlMapper.update(
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        value.highestRevision(),
                        json.write(value),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        command.source().command().tenantId(),
                        command.id().toString(),
                        value.version() - 1,
                        json.write(value.input()),
                        command.digest());
        if (count != 1) throw conflict();
        append(value);
    }

    /** 只按持久原租户查找，不提供跨租户操作号旁路。 */
    public Optional<VoucherReversalOperation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), row()).stream().findFirst();
    }

    /** 原件只查询未结束命令，只有明确保存安全结束才释放占用。 */
    public Optional<VoucherReversalOperation> forOriginal(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.forOriginal(tenant, id.toString()), row()).stream()
                .findFirst();
    }

    /** 安全结束引用停止前的原修订，不能以当前失败覆盖曾经发送的不同事实。 */
    public Optional<VoucherReversalOperation> revision(String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            VoucherReversalOperation.class);
                            var command = value.input().command();
                            if (!command.source().command().tenantId().equals(tenant)
                                    || !command.id().equals(id)
                                    || value.version() != version)
                                throw new IllegalStateException(
                                        "Persisted reversal revision identity is inconsistent");
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 已结束操作的历史记录继续可读，不能据此重新发送。 */
    public Optional<VoucherReversalRetirement> retirement(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.retirement(tenant, id.toString()), retirementRow()).stream()
                .findFirst();
    }

    /** 原凭证的所有结束依据按时间保留，页面不把新命令冒充原命令重试。 */
    public List<VoucherReversalRetirement> retirements(String tenant, UUID original) {
        return SqlRows.map(sqlMapper.retirements(tenant, original.toString()), retirementRow());
    }

    /** 核实精确停止和恢复修订后保存结束事实、释放唯一占用；任一步冲突整笔事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(VoucherReversalRetirement value) {
        var before =
                revision(value.tenantId(), value.reversalId(), value.reversalVersion())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        var stopped =
                find(value.tenantId(), value.reversalId())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        var original =
                originals
                        .revision(value.tenantId(), value.operationId(), value.originalVersion())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        var restored =
                originals
                        .find(value.tenantId(), value.operationId())
                        .orElseThrow(JdbcVoucherReversalOperationRepository::conflict);
        if (VoucherReversalRetirement.requireSource(
                                before, original, value.retiredBy(), value.retiredAt())
                        != value.basis()
                || !value.original().equals(original.observation())
                || value.stoppedVersion() != stopped.version()
                || value.releasedVersion() != restored.version()
                || !stopped.equals(before.stopForRetirement(value.retiredAt()))
                || !restored.equals(
                        original.releaseReversal(value.reversalId(), value.retiredAt())))
            throw conflict();
        try {
            sqlMapper.retire(
                    value.tenantId(),
                    value.id().toString(),
                    value.operationId().toString(),
                    value.reversalId().toString(),
                    value.originalVersion(),
                    value.releasedVersion(),
                    value.reversalVersion(),
                    value.stoppedVersion(),
                    value.basis().name(),
                    value.retiredBy(),
                    timestamp(value.retiredAt()),
                    json.write(value));
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
        if (sqlMapper.retire2(
                        timestamp(value.retiredAt()),
                        value.tenantId(),
                        value.reversalId().toString(),
                        value.stoppedVersion(),
                        value.operationId().toString())
                != 1) throw conflict();
    }

    /** 每批十项，过期领取交给状态机转为原编号查询。 */
    public List<Candidate> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), timestamp(now)),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    private Function<SqlRow, VoucherReversalOperation> row() {
        return row -> {
            var value = json.read(row.getString("state_json"), VoucherReversalOperation.class);
            var input = value.input();
            var command = input.command();
            var original = command.source().command();
            if (!input.equals(
                            json.read(
                                    row.getString("input_json"),
                                    VoucherReversalOperation.Input.class))
                    || !command.digest().equals(row.getString("command_digest"))
                    || !original.tenantId().equals(row.getString("tenant_id"))
                    || !command.id().toString().equals(row.getString("id"))
                    || !original.id().toString().equals(row.getString("operation_id"))
                    || input.originalVersion() != row.getLong("original_version")
                    || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status"))
                    || value.attempts() != row.getInt("attempts")
                    || value.highestRevision() != row.getLong("highest_revision")
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(
                            value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at")))
                    || !Objects.equals(
                            value.leaseUntil(), instant(row.getTimestamp("lease_until")))) {
                throw new IllegalStateException(
                        "Persisted reversal operation identity is inconsistent");
            }
            var retiredAt = instant(row.getTimestamp("retired_at"));
            var active = row.getString("active_operation_id");
            if (retiredAt == null
                    ? !original.id().toString().equals(active)
                    : active != null
                            || !retirement(original.tenantId(), command.id())
                                    .filter(
                                            record ->
                                                    record.retiredAt().equals(retiredAt)
                                                            && record.stoppedVersion()
                                                                    == value.version())
                                    .isPresent()) {
                throw new IllegalStateException(
                        "Persisted reversal active binding is inconsistent");
            }
            return value;
        };
    }

    private Function<SqlRow, VoucherReversalRetirement> retirementRow() {
        return row -> {
            var value =
                    json.read(row.getString("retirement_json"), VoucherReversalRetirement.class);
            if (!value.tenantId().equals(row.getString("tenant_id"))
                    || !value.id().toString().equals(row.getString("id"))
                    || !value.operationId().toString().equals(row.getString("operation_id"))
                    || !value.reversalId().toString().equals(row.getString("reversal_id"))
                    || value.originalVersion() != row.getLong("original_version")
                    || value.releasedVersion() != row.getLong("released_version")
                    || value.reversalVersion() != row.getLong("reversal_version")
                    || value.stoppedVersion() != row.getLong("stopped_version")
                    || !value.basis().name().equals(row.getString("basis"))
                    || !value.retiredBy().equals(row.getString("retired_by"))
                    || !value.retiredAt().equals(instant(row.getTimestamp("retired_at"))))
                throw new IllegalStateException(
                        "Persisted reversal retirement identity is inconsistent");
            return value;
        };
    }

    private void append(VoucherReversalOperation value) {
        var command = value.input().command();
        sqlMapper.append(
                command.source().command().tenantId(),
                command.id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }

    private static Instant instant(Timestamp at) { return at == null ? null : at.toInstant(); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Reversal command, consumed preparation or original voucher changed"); }

    /**
     * 扫描不读取完整会计或支付内容。
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
