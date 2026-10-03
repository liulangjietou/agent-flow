package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每笔原银行只保留一个未结束的调整，原文与连续修订为重启查询提供持久依据。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPayableAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierAdjustmentPreparationRepository preparations;
    private final JdbcSupplierAdjustmentSources sources;

    /** 核对真实准备、银行与本地占用，不能从任意看似合法的命令直接发起调整。 */
    public JdbcSupplierPayableAdjustmentRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierAdjustmentPreparationRepository preparations,
            JdbcSupplierAdjustmentSources sources) {
        this.jdbc = jdbc; this.json = json; this.preparations = preparations; this.sources = sources;
    }

    /** 实际领取、原成功银行和仍在途占用一致后创建，与准备 READY 在同一上层事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierAdjustmentPreparation preparation, SupplierPayableAdjustmentOperation value) {
        var command = value.command(); var bank = command.source().returns().request().command();
        var reservation = bank.holdCommand().authorization().source().reservation();
        sources.requireCurrent(command.source());
        if (!preparation.equals(preparations.find(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict))
                || preparation.ready(command, value.createdAt()).status() != SupplierAdjustmentPreparation.Status.READY
                || !value.equals(SupplierPayableAdjustmentOperation.queue(command, value.createdAt()))) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO supplier_payable_adjustment_operation(tenant_id,id,preparation_version,payment_id,return_version,reservation_id,command_json,command_digest,state_json,
                    version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at,active_payment_id)
                    VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?,?)
                    """, command.tenantId(), command.id().toString(), preparation.version(), command.source().returns().request().command().id().toString(), command.source().returns().version(), reservation.id().toString(),
                    json.write(command), command.digest(), json.write(value), timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), command.source().returns().request().command().id().toString());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("SUPPLIER_ADJUSTMENT_PENDING", "Original bank payment already has an active adjustment command"); }
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
    public SupplierPayableAdjustmentOperation resolve(SupplierAdjustmentDisputeResolution decision) {
        var locked = jdbc.queryForList("SELECT id FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND id=? FOR UPDATE",
                String.class, decision.tenantId(), decision.adjustmentId().toString());
        if (locked.isEmpty()) throw conflict();
        var before = find(decision.tenantId(), decision.adjustmentId()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var after = decision.resolve(before, resolutionHistory(before));
        persist(after);
        jdbc.update("""
                INSERT INTO supplier_adjustment_dispute_resolution(tenant_id,id,adjustment_id,disputed_version,resolved_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, decision.tenantId(), decision.id().toString(), decision.adjustmentId().toString(), decision.disputedVersion(), decision.resolvedVersion(),
                decision.observation().status().name(), decision.resolvedBy(), timestamp(decision.observation().observedAt()), timestamp(decision.resolvedAt()), json.write(decision));
        return after;
    }

    /** 连续修订中的成功与已调整提示均保留，后续候选不能把历史消费改成未调整。 */
    public SupplierPayableAdjustmentOperation.ResolutionHistory resolutionHistory(String tenant, UUID id) {
        return resolutionHistory(find(tenant, id).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict));
    }

    /** 读取最新决定时核对原修订与当时历史，不让后续原号查询改写旧决定。 */
    public Optional<SupplierAdjustmentDisputeResolution> latestResolution(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_adjustment_dispute_resolution WHERE tenant_id=? AND adjustment_id=? ORDER BY resolved_version DESC LIMIT 1", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierAdjustmentDisputeResolution.class);
            if (!tenant.equals(value.tenantId()) || !id.equals(value.adjustmentId()) || !value.id().toString().equals(row.getString("id"))
                    || value.disputedVersion() != row.getLong("disputed_version") || value.resolvedVersion() != row.getLong("resolved_version")
                    || !value.observation().status().name().equals(row.getString("outcome")) || !value.resolvedBy().equals(row.getString("resolved_by"))
                    || !value.observation().observedAt().truncatedTo(ChronoUnit.MICROS).equals(instant(row.getTimestamp("observed_at")))
                    || !value.resolvedAt().truncatedTo(ChronoUnit.MICROS).equals(instant(row.getTimestamp("resolved_at")))) throw conflict();
            var before = revision(tenant, id, value.disputedVersion()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
            var after = revision(tenant, id, value.resolvedVersion()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
            if (!value.resolve(before, resolutionHistory(before)).equals(after)) throw conflict();
            return value;
        }, tenant, id.toString()).stream().findFirst();
    }

    private SupplierPayableAdjustmentOperation.ResolutionHistory resolutionHistory(SupplierPayableAdjustmentOperation current) {
        var command = current.command();
        return jdbc.query("SELECT version,state_json FROM supplier_payable_adjustment_revision WHERE tenant_id=? AND operation_id=? AND version<=? ORDER BY version", rows -> {
            SupplierPayableAdjustmentObservation firstAdjustment = null; boolean adjustmentObserved = false;
            long version = 0; SupplierPayableAdjustmentOperation previous = null;
            while (rows.next()) {
                var value = json.read(rows.getString("state_json"), SupplierPayableAdjustmentOperation.class);
                if (++version != rows.getLong("version") || value.version() != version || !value.command().equals(command)) throw conflict();
                if (firstAdjustment == null && value.adjusted()) firstAdjustment = value.observation();
                adjustmentObserved |= SupplierPayableAdjustmentOperation.adjustmentRisk(value.observation())
                        || SupplierPayableAdjustmentOperation.adjustmentRisk(value.conflictingObservation()); previous = value;
            }
            if (!current.equals(previous)) throw conflict();
            return new SupplierPayableAdjustmentOperation.ResolutionHistory(firstAdjustment, adjustmentObserved);
        }, command.tenantId(), command.id().toString(), current.version());
    }

    private void persist(SupplierPayableAdjustmentOperation value) {
        var command = value.command();
        int changed = jdbc.update("""
                UPDATE supplier_payable_adjustment_operation SET state_json=?,version=?,status=?,attempts=?,dispatches=?,highest_revision=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND command_json=? AND command_digest=? AND retired_version IS NULL
                """, json.write(value), value.version(), value.status().name(), value.attempts(), value.dispatches(), value.highestRevision(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()),
                timestamp(value.leaseUntil()), command.tenantId(), command.id().toString(), value.version() - 1, json.write(command), command.digest());
        if (changed != 1) throw conflict(); append(value);
    }

    /** 历史同样核对不可变命令及实际准备和银行修订。 */
    public Optional<SupplierPayableAdjustmentOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 未知、已调整和争议都保留原银行独占，避免另建调整绕过恢复。 */
    public Optional<SupplierPayableAdjustmentOperation> active(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND active_payment_id=?", this::restore, tenant, paymentId.toString()).stream().findFirst();
    }

    /** 原银行的历史尝试保持各自编号和日期，不覆盖已结束决定。 */
    public List<SupplierPayableAdjustmentOperation> history(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND payment_id=? ORDER BY created_at,id", this::restore, tenant, paymentId.toString());
    }

    /** 对外历史有界分页，游标先由应用层验证属于同一原银行。 */
    public List<SupplierPayableAdjustmentOperation> page(String tenant, UUID paymentId, SupplierPayableAdjustmentOperation before, int limit) {
        if (before == null) return jdbc.query("SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND payment_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                this::restore, tenant, paymentId.toString(), limit + 1);
        return jdbc.query("""
                SELECT * FROM supplier_payable_adjustment_operation WHERE tenant_id=? AND payment_id=? AND (created_at<? OR (created_at=? AND id<?))
                ORDER BY created_at DESC,id DESC LIMIT ?
                """, this::restore, tenant, paymentId.toString(), timestamp(before.createdAt()), timestamp(before.createdAt()), before.command().id().toString(), limit + 1);
    }

    /** 完成和结束决定都引用已经落库的精确修订。 */
    public Optional<SupplierPayableAdjustmentOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_payable_adjustment_revision WHERE tenant_id=? AND operation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPayableAdjustmentOperation.class);
            if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != version) throw conflict(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 安全证据先落库，再解除原银行独占；任一步失败由原申请事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String tenant, SupplierAdjustmentRetirement decision) {
        var current = find(tenant, decision.operationId()).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var proof = retirementProof(tenant, decision);
        if (!proof.equals(current) || retirement(tenant, decision.operationId()).isPresent()) throw conflict();
        jdbc.update("INSERT INTO supplier_adjustment_retirement(tenant_id,operation_id,payment_id,operation_version,basis,retired_by,retired_at,state_json) VALUES(?,?,?,?,?,?,?,?)",
                tenant, decision.operationId().toString(), decision.paymentId().toString(), decision.operationVersion(), decision.basis().name(), decision.retiredBy(), timestamp(decision.retiredAt()), json.write(decision));
        int changed = jdbc.update("""
                UPDATE supplier_payable_adjustment_operation SET active_payment_id=NULL,retired_version=? WHERE tenant_id=? AND id=? AND version=?
                AND active_payment_id=payment_id AND retired_version IS NULL
                """, decision.operationVersion(), tenant, decision.operationId().toString(), decision.operationVersion());
        if (changed != 1) throw conflict();
    }

    /** 结束标记须有实际安全修订支持，不能只靠状态字符串解除独占。 */
    public Optional<SupplierAdjustmentRetirement> retirement(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_adjustment_retirement WHERE tenant_id=? AND operation_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierAdjustmentRetirement.class);
            if (!value.operationId().equals(id) || !value.paymentId().toString().equals(row.getString("payment_id")) || value.operationVersion() != row.getLong("operation_version")
                    || !value.basis().name().equals(row.getString("basis")) || !value.retiredBy().equals(row.getString("retired_by")) || !time(value.retiredAt()).equals(instant(row.getTimestamp("retired_at")))) throw conflict();
            retirementProof(tenant, value); return value;
        }, tenant, id.toString()).stream().findFirst();
    }

    /** 恢复扫描排除已结束尝试；状态未知只能由领域领取原号查询。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM supplier_payable_adjustment_operation WHERE retired_version IS NULL AND ((status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?)
                OR (status IN ('CHECKING','ADJUSTING','QUERYING') AND lease_until<=?)) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now), timestamp(now));
    }

    /** ERP 已确认但本地暂等银行复核时，只补原占用完成，不再调用任何调整写入。 */
    public List<Candidate> awaitingLocalCompletion() {
        return jdbc.query("""
                SELECT tenant_id,id FROM supplier_payable_adjustment_operation
                WHERE status='ADJUSTED' AND retired_version IS NULL AND completed_version IS NULL
                ORDER BY updated_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))));
    }

    // 与账本、分录和占用同事务调用；后续查询只改变状态，不得再次占用办理位置。
    void complete(SupplierAdjustmentCompletion proof) {
        var operation = proof.operation(); var command = operation.command();
        int changed = jdbc.update("""
                UPDATE supplier_payable_adjustment_operation SET completed_version=?,active_payment_id=NULL
                WHERE tenant_id=? AND id=? AND version=? AND state_json=? AND status='ADJUSTED'
                    AND retired_version IS NULL AND completed_version IS NULL AND active_payment_id=payment_id
                    AND EXISTS(SELECT 1 FROM supplier_adjustment_completion c WHERE c.tenant_id=? AND c.operation_id=? AND c.operation_version=?)
                """, operation.version(), command.tenantId(), command.id().toString(), operation.version(), json.write(operation),
                command.tenantId(), command.id().toString(), operation.version());
        if (changed != 1) throw conflict();
    }
    private void requireCompletion(SupplierPayableAdjustmentOperation value, long completedVersion) {
        var command = value.command();
        var proof = revision(command.tenantId(), command.id(), completedVersion).orElseThrow(JdbcSupplierPayableAdjustmentRepository::conflict);
        var matches = jdbc.query("""
                SELECT payment_id,accounted_entry_count FROM supplier_adjustment_completion
                WHERE tenant_id=? AND operation_id=? AND operation_version=?
                """, (row, index) -> command.source().returns().request().command().id().toString().equals(row.getString("payment_id"))
                    && command.source().returns().entries().size() == row.getInt("accounted_entry_count"), command.tenantId(), command.id().toString(), completedVersion);
        if (!proof.adjusted() || !proof.command().equals(command) || matches.size() != 1 || !matches.get(0)) throw conflict();
    }
    private SupplierPayableAdjustmentOperation restore(ResultSet row, int index) throws SQLException {
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
        jdbc.update("INSERT INTO supplier_payable_adjustment_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)", value.command().tenantId(), value.command().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(time(value)); }
    private static Instant time(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MICROS); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted supplier payable adjustment is inconsistent"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier adjustment or persisted source changed"); }

    /**
     * 后台扫描只返回调整身份。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
