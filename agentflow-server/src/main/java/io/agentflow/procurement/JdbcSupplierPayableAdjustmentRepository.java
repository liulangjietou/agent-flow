package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.observability.DiagnosticContext;
import io.agentflow.procurement.mapper.SupplierPayableAdjustmentRepositoryMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 每笔原银行只保留一个未结束的调整，原文与连续修订为重启查询提供持久依据。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPayableAdjustmentRepository {
    private final SupplierPayableAdjustmentRepositoryMapper sqlMapper;
    private final JsonUtil json;
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final JdbcSupplierAdjustmentSources sources;

    /** 核对真实准备、银行与本地占用，不能从任意看似合法的命令直接发起调整。 */
    public JdbcSupplierPayableAdjustmentRepository(
            SupplierPayableAdjustmentRepositoryMapper sqlMapper,
            JsonUtil json,
            JdbcSupplierAdjustmentPreparationRepository preparations,
            JdbcSupplierAdjustmentSources sources) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.preparations = preparations;
        this.sources = sources;
    }

    /** 实际领取、原成功银行和仍在途占用一致后创建，与准备 READY 在同一上层事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(
            SupplierAdjustmentPreparation preparation, SupplierPayableAdjustmentOperation value) {
        var command = value.command();
        var bank = command.source().returns().request().command();
        var reservation = bank.holdCommand().authorization().source().reservation();
        sources.requireCurrent(command.source());
        if (!preparation.equals(
                        preparations
                                .find(command.tenantId(), command.id())
                                .orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict))
                || preparation.ready(command, value.createdAt()).status()
                        != SupplierAdjustmentPreparation.Status.READY
                || !value.equals(
                        SupplierPayableAdjustmentOperation.queue(command, value.createdAt())))
            throw conflict();
        try {
            sqlMapper.create(
                    DiagnosticContext.capture().traceId(),
                    command.tenantId(),
                    command.id().toString(),
                    preparation.version(),
                    command.source().returns().request().command().id().toString(),
                    command.source().returns().version(),
                    reservation.id().toString(),
                    json.write(command),
                    command.digest(),
                    json.write(value),
                    timestamp(value.createdAt()),
                    timestamp(value.updatedAt()),
                    timestamp(value.nextAttemptAt()),
                    command.source().returns().request().command().id().toString());
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    "SUPPLIER_ADJUSTMENT_PENDING",
                    "Original bank payment already has an active adjustment command");
        }
        append(value);
    }

    /** 原文和前版参与条件更新；已安全结束的命令不可再次领取、查询或重试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPayableAdjustmentOperation value) {
        var before = find(value.command().tenantId(), value.command().id()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        if (before.version() != value.version() - 1 || before.conflictingObservation() != null && value.conflictingObservation() == null) throw conflict();
        persist(value);
    }

    /** 解除争议与具名决定原子保存，普通状态写入不能绕过这条路径。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPayableAdjustmentOperation resolve(
            SupplierAdjustmentDisputeResolution decision) {
        var locked = sqlMapper.resolve(decision.tenantId(), decision.adjustmentId().toString());
        if (locked.isEmpty()) throw conflict();
        var before =
                find(decision.tenantId(), decision.adjustmentId())
                        .orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var after = decision.resolve(before, resolutionHistory(before));
        persist(after);
        sqlMapper.resolve2(
                decision.tenantId(),
                decision.id().toString(),
                decision.adjustmentId().toString(),
                decision.disputedVersion(),
                decision.resolvedVersion(),
                decision.observation().status().name(),
                decision.resolvedBy(),
                timestamp(decision.observation().observedAt()),
                timestamp(decision.resolvedAt()),
                json.write(decision));
        return after;
    }

    /** 连续修订中的成功与已调整提示均保留，后续候选不能把历史消费改成未调整。 */
    public SupplierPayableAdjustmentOperation.ResolutionHistory resolutionHistory(String tenant, UUID id) {
        return resolutionHistory(find(tenant, id).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict));
    }

    /** 读取最新决定时核对原修订与当时历史，不让后续原号查询改写旧决定。 */
    public Optional<SupplierAdjustmentDisputeResolution> latestResolution(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.latestResolution(tenant, id.toString()),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierAdjustmentDisputeResolution.class);
                            if (!tenant.equals(value.tenantId())
                                    || !id.equals(value.adjustmentId())
                                    || !value.id().toString().equals(row.getString("id"))
                                    || value.disputedVersion() != row.getLong("disputed_version")
                                    || value.resolvedVersion() != row.getLong("resolved_version")
                                    || !value.observation()
                                            .status()
                                            .name()
                                            .equals(row.getString("outcome"))
                                    || !value.resolvedBy().equals(row.getString("resolved_by"))
                                    || !value.observation()
                                            .observedAt()
                                            .truncatedTo(ChronoUnit.MICROS)
                                            .equals(instant(row.getTimestamp("observed_at")))
                                    || !value.resolvedAt()
                                            .truncatedTo(ChronoUnit.MICROS)
                                            .equals(instant(row.getTimestamp("resolved_at"))))
                                throw conflict();
                            var before =
                                    revision(tenant, id, value.disputedVersion())
                                            .orElseThrow(
                                                    JdbcSupplierPayableAdjustmentRepository
                                                            ::conflict);
                            var after =
                                    revision(tenant, id, value.resolvedVersion())
                                            .orElseThrow(
                                                    JdbcSupplierPayableAdjustmentRepository
                                                            ::conflict);
                            if (!value.resolve(before, resolutionHistory(before)).equals(after))
                                throw conflict();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    private SupplierPayableAdjustmentOperation.ResolutionHistory resolutionHistory(
            SupplierPayableAdjustmentOperation current) {
        var command = current.command();
        return restoreResolutionHistory(
                current,
                sqlMapper.resolutionHistoryRows(
                        new Object[] {
                            command.tenantId(), command.id().toString(), current.version()
                        }));
    }

    private void persist(SupplierPayableAdjustmentOperation value) {
        var command = value.command();
        int changed =
                sqlMapper.persist(
                        json.write(value),
                        value.version(),
                        value.status().name(),
                        value.attempts(),
                        value.dispatches(),
                        value.highestRevision(),
                        timestamp(value.updatedAt()),
                        timestamp(value.nextAttemptAt()),
                        timestamp(value.leaseUntil()),
                        command.tenantId(),
                        command.id().toString(),
                        value.version() - 1,
                        json.write(command),
                        command.digest());
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 历史同样核对不可变命令及实际准备和银行修订。 */
    public Optional<SupplierPayableAdjustmentOperation> find(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.find(tenant, id.toString()), this::restore).stream()
                .findFirst();
    }

    /** 未知、已调整和争议都保留原银行独占，避免另建调整绕过恢复。 */
    public Optional<SupplierPayableAdjustmentOperation> active(String tenant, UUID paymentId) {
        return SqlRows.map(sqlMapper.active(tenant, paymentId.toString()), this::restore).stream()
                .findFirst();
    }

    /** 原银行的历史尝试保持各自编号和日期，不覆盖已结束决定。 */
    public List<SupplierPayableAdjustmentOperation> history(String tenant, UUID paymentId) {
        return SqlRows.map(sqlMapper.history(tenant, paymentId.toString()), this::restore);
    }

    /** 对外历史有界分页，游标先由应用层验证属于同一原银行。 */
    public List<SupplierPayableAdjustmentOperation> page(
            String tenant, UUID paymentId, SupplierPayableAdjustmentOperation before, int limit) {
        if (before == null)
            return SqlRows.map(
                    sqlMapper.page(tenant, paymentId.toString(), limit + 1), this::restore);
        return SqlRows.map(
                sqlMapper.page2(
                        tenant,
                        paymentId.toString(),
                        timestamp(before.createdAt()),
                        timestamp(before.createdAt()),
                        before.command().id().toString(),
                        limit + 1),
                this::restore);
    }

    /** 完成和结束决定都引用已经落库的精确修订。 */
    public Optional<SupplierPayableAdjustmentOperation> revision(
            String tenant, UUID id, long version) {
        return SqlRows.map(
                        sqlMapper.revision(tenant, id.toString(), version),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierPayableAdjustmentOperation.class);
                            if (!value.command().tenantId().equals(tenant)
                                    || !value.command().id().equals(id)
                                    || value.version() != version) throw conflict();
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 安全证据先落库，再解除原银行独占；任一步失败由原申请事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String tenant, SupplierAdjustmentRetirement decision) {
        var current =
                find(tenant, decision.operationId())
                        .orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var proof = retirementProof(tenant, decision);
        if (!proof.equals(current) || retirement(tenant, decision.operationId()).isPresent())
            throw conflict();
        sqlMapper.retire(
                tenant,
                decision.operationId().toString(),
                decision.paymentId().toString(),
                decision.operationVersion(),
                decision.basis().name(),
                decision.retiredBy(),
                timestamp(decision.retiredAt()),
                json.write(decision));
        int changed =
                sqlMapper.retire2(
                        decision.operationVersion(),
                        tenant,
                        decision.operationId().toString(),
                        decision.operationVersion());
        if (changed != 1) throw conflict();
    }

    /** 结束标记须有实际安全修订支持，不能只靠状态字符串解除独占。 */
    public Optional<SupplierAdjustmentRetirement> retirement(String tenant, UUID id) {
        return SqlRows.map(
                        sqlMapper.retirement(tenant, id.toString()),
                        row -> {
                            var value =
                                    json.read(
                                            row.getString("state_json"),
                                            SupplierAdjustmentRetirement.class);
                            if (!value.operationId().equals(id)
                                    || !value.paymentId()
                                            .toString()
                                            .equals(row.getString("payment_id"))
                                    || value.operationVersion() != row.getLong("operation_version")
                                    || !value.basis().name().equals(row.getString("basis"))
                                    || !value.retiredBy().equals(row.getString("retired_by"))
                                    || !time(value.retiredAt())
                                            .equals(instant(row.getTimestamp("retired_at"))))
                                throw conflict();
                            retirementProof(tenant, value);
                            return value;
                        })
                .stream()
                .findFirst();
    }

    /** 恢复扫描排除已结束尝试；状态未知只能由领域领取原号查询。 */
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

    /** ERP 已确认但本地暂等银行复核时，只补原占用完成，不再调用任何调整写入。 */
    public List<Candidate> awaitingLocalCompletion() {
        return SqlRows.map(
                sqlMapper.awaitingLocalCompletion(),
                row ->
                        new Candidate(
                                row.getString("tenant_id"),
                                UUID.fromString(row.getString("id")),
                                row.getString("trace_id"),
                                row.getString("business_no"),
                                row.getString("process_instance_id")));
    }

    // 与账本、分录和占用同事务调用；后续查询只改变状态，不得再次占用办理位置。
    void complete(SupplierAdjustmentCompletion proof) {
        var operation = proof.operation();
        var command = operation.command();
        int changed =
                sqlMapper.complete(
                        operation.version(),
                        command.tenantId(),
                        command.id().toString(),
                        operation.version(),
                        json.write(operation),
                        command.tenantId(),
                        command.id().toString(),
                        operation.version());
        if (changed != 1) throw conflict();
    }

    private void requireCompletion(
            SupplierPayableAdjustmentOperation value, long completedVersion) {
        var command = value.command();
        var proof =
                revision(command.tenantId(), command.id(), completedVersion)
                        .orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var matches =
                SqlRows.map(
                        sqlMapper.requireCompletion(
                                command.tenantId(), command.id().toString(), completedVersion),
                        row ->
                                command.source()
                                                .returns()
                                                .request()
                                                .command()
                                                .id()
                                                .toString()
                                                .equals(row.getString("payment_id"))
                                        && command.source().returns().entries().size()
                                                == row.getInt("accounted_entry_count"));
        if (!proof.adjusted()
                || !proof.command().equals(command)
                || matches.size() != 1
                || !matches.get(0)) throw conflict();
    }

    private SupplierPayableAdjustmentOperation restore(SqlRow row) {
        var value = json.read(row.getString("state_json"), SupplierPayableAdjustmentOperation.class); var command = value.command();
        if (!command.equals(json.read(row.getString("command_json"), SupplierPayableAdjustmentCommand.class)) || !command.digest().equals(row.getString("command_digest"))
                || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                || !command.source().returns().request().command().id().toString().equals(row.getString("payment_id")) || command.source().returns().version() != row.getLong("return_version")
                || !command.source().returns().request().command().holdCommand().authorization().source().reservation().id().toString().equals(row.getString("reservation_id"))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || value.dispatches() != row.getInt("dispatches") || value.highestRevision() != row.getLong("highest_revision")
                || !time(value.createdAt()).equals(instant(row.getTimestamp("created_at"))) || !time(value.updatedAt()).equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(time(value.nextAttemptAt()), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(time(value.leaseUntil()), instant(row.getTimestamp("lease_until")))) throw inconsistent();
        var preparation = preparations.revision(command.tenantId(), command.id(), row.getLong("preparation_version")).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        sources.requireRecorded(command.source());
        if (!command.source().equals(preparation.input().source())
                || preparation.ready(command, value.createdAt()).status() != SupplierAdjustmentPreparation.Status.READY) throw conflict();
        Long retiredVersion = row.getObject("retired_version", Long.class);
        Long completedVersion = row.getObject("completed_version", Long.class);
        if (!Objects.equals(retiredVersion == null && completedVersion == null ? command.source().returns().request().command().id().toString() : null, row.getString("active_payment_id"))) throw inconsistent();
        if (completedVersion != null) requireCompletion(value, completedVersion);
        if (retiredVersion != null) {
            var decision = retirement(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
            if (decision.operationVersion() != retiredVersion || !decision.matches(value)) throw conflict();
        }
        return value;
    }

    private SupplierPayableAdjustmentOperation retirementProof(String tenant, SupplierAdjustmentRetirement decision) {
        var proof = revision(tenant, decision.operationId(), decision.operationVersion()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        if (!decision.matches(proof)) throw conflict(); return proof;
    }

    private void append(SupplierPayableAdjustmentOperation value) {
        sqlMapper.append(
                value.command().tenantId(),
                value.command().id().toString(),
                value.version(),
                json.write(value));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(time(value)); }

    private static Instant time(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MICROS); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted supplier payable adjustment is inconsistent"); }

    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier adjustment or persisted source changed"); }

    /**
     * 后台扫描只返回调整身份。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(
            String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 原业务关联不存在时保持空值，不借用当前审批轮次或工作线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }

        /** 旧候选缺来源时由工作器建立稳定诊断作用域。 */
        public Candidate(String tenantId, UUID id) { this(tenantId, id, null); }
    }

    /** 从不可变历史行恢复 resolutionHistory 的领域证据。 */
    private SupplierPayableAdjustmentOperation.ResolutionHistory restoreResolutionHistory(
            SupplierPayableAdjustmentOperation current, java.util.List<SqlRow> persistedRows) {
        var command = current.command();
        SupplierPayableAdjustmentObservation firstAdjustment = null;
        boolean adjustmentObserved = false;
        long version = 0;
        SupplierPayableAdjustmentOperation previous = null;
        for (var rows : persistedRows) {
            var value =
                    json.read(
                            rows.getString("state_json"), SupplierPayableAdjustmentOperation.class);
            if (++version != rows.getLong("version")
                    || value.version() != version
                    || !value.command().equals(command)) throw conflict();
            if (firstAdjustment == null && value.adjusted()) firstAdjustment = value.observation();
            adjustmentObserved |=
                    SupplierPayableAdjustmentOperation.adjustmentRisk(value.observation())
                            || SupplierPayableAdjustmentOperation.adjustmentRisk(
                                    value.conflictingObservation());
            previous = value;
        }
        if (!current.equals(previous)) throw conflict();
        return new SupplierPayableAdjustmentOperation.ResolutionHistory(
                firstAdjustment, adjustmentObserved);
    }
}
