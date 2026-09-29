package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * 每笔原银行只保留一个未安全结束的结算，原文与连续修订为重启查询提供持久依据。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcSupplierPayableSettlementRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcSupplierSettlementPreparationRepository preparations;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final JdbcProcurementPayableReservationRepository reservations;

    /** 核对真实准备、银行与本地占用，不能从任意看似合法的命令直接发起核销。 */
    public JdbcSupplierPayableSettlementRepository(JdbcTemplate jdbc, JsonUtil json, JdbcSupplierSettlementPreparationRepository preparations,
            JdbcSupplierPaymentOperationRepository payments, JdbcProcurementPayableReservationRepository reservations) {
        this.jdbc = jdbc; this.json = json; this.preparations = preparations; this.payments = payments; this.reservations = reservations;
    }

    /** 实际领取、原成功银行和仍在途占用一致后创建，与准备 READY 在同一上层事务提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(SupplierSettlementPreparation preparation, SupplierPayableSettlementOperation value) {
        var command = value.command(); var original = preparation.input().payment(); var reservation = command.payment().holdCommand().authorization().source().reservation();
        var currentBank = payments.find(command.tenantId(), command.payment().id()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
        if (!preparation.equals(preparations.find(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict))
                || !payments.revision(command.tenantId(), command.payment().id(), command.paymentVersion()).filter(original::equals).isPresent()
                || !command.matchesCurrentPayment(currentBank, command.paid(), value.createdAt())
                || !reservations.active(command.tenantId(), reservation.source().requestId()).filter(reservation::equals).isPresent()
                || preparation.ready(command, value.createdAt()).status() != SupplierSettlementPreparation.Status.READY
                || !value.equals(SupplierPayableSettlementOperation.queue(command, value.createdAt()))) throw conflict();
        try {
            jdbc.update("""
                    INSERT INTO supplier_payable_settlement_operation(tenant_id,id,preparation_version,payment_id,payment_version,reservation_id,command_json,command_digest,state_json,
                    version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at,active_payment_id)
                    VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?,?)
                    """, command.tenantId(), command.id().toString(), preparation.version(), command.payment().id().toString(), command.paymentVersion(), reservation.id().toString(),
                    json.write(command), command.digest(), json.write(value), timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), command.payment().id().toString());
        } catch (DuplicateKeyException duplicate) { throw new DomainException("SUPPLIER_SETTLEMENT_PENDING", "Original bank payment already has an active settlement command"); }
        append(value);
    }

    /** 原文和前版参与条件更新；已安全结束的命令不可再次领取、查询或重试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(SupplierPayableSettlementOperation value) {
        var command = value.command();
        int changed = jdbc.update("""
                UPDATE supplier_payable_settlement_operation SET state_json=?,version=?,status=?,attempts=?,dispatches=?,highest_revision=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND command_json=? AND command_digest=? AND active_payment_id=payment_id AND retired_version IS NULL
                """, json.write(value), value.version(), value.status().name(), value.attempts(), value.dispatches(), value.highestRevision(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()),
                timestamp(value.leaseUntil()), command.tenantId(), command.id().toString(), value.version() - 1, json.write(command), command.digest());
        if (changed != 1) throw conflict(); append(value);
    }

    /** 历史同样核对不可变命令及实际准备和银行修订。 */
    public Optional<SupplierPayableSettlementOperation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_payable_settlement_operation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }

    /** 未知、已结算和争议都保留原银行独占，避免另建结算绕过恢复。 */
    public Optional<SupplierPayableSettlementOperation> active(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_payable_settlement_operation WHERE tenant_id=? AND active_payment_id=?", this::restore, tenant, paymentId.toString()).stream().findFirst();
    }

    /** 原银行的历史尝试保持各自编号和日期，不覆盖已结束决定。 */
    public List<SupplierPayableSettlementOperation> history(String tenant, UUID paymentId) {
        return jdbc.query("SELECT * FROM supplier_payable_settlement_operation WHERE tenant_id=? AND payment_id=? ORDER BY created_at,id", this::restore, tenant, paymentId.toString());
    }

    /** 完成和结束决定都引用已经落库的精确修订。 */
    public Optional<SupplierPayableSettlementOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM supplier_payable_settlement_revision WHERE tenant_id=? AND operation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierPayableSettlementOperation.class);
            if (!value.command().tenantId().equals(tenant) || !value.command().id().equals(id) || value.version() != version) throw conflict(); return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }

    /** 安全证据先落库，再解除原银行独占；任一步失败由原申请事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(String tenant, SupplierSettlementRetirement decision) {
        var current = find(tenant, decision.operationId()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
        var proof = retirementProof(tenant, decision);
        if (!proof.equals(current) || retirement(tenant, decision.operationId()).isPresent()) throw conflict();
        jdbc.update("INSERT INTO supplier_settlement_retirement(tenant_id,operation_id,payment_id,operation_version,basis,retired_by,retired_at,state_json) VALUES(?,?,?,?,?,?,?,?)",
                tenant, decision.operationId().toString(), decision.paymentId().toString(), decision.operationVersion(), decision.basis().name(), decision.retiredBy(), timestamp(decision.retiredAt()), json.write(decision));
        int changed = jdbc.update("""
                UPDATE supplier_payable_settlement_operation SET active_payment_id=NULL,retired_version=? WHERE tenant_id=? AND id=? AND version=?
                AND active_payment_id=payment_id AND retired_version IS NULL
                """, decision.operationVersion(), tenant, decision.operationId().toString(), decision.operationVersion());
        if (changed != 1) throw conflict();
    }

    /** 结束标记须有实际安全修订支持，不能只靠状态字符串解除独占。 */
    public Optional<SupplierSettlementRetirement> retirement(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM supplier_settlement_retirement WHERE tenant_id=? AND operation_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), SupplierSettlementRetirement.class);
            if (!value.operationId().equals(id) || !value.paymentId().toString().equals(row.getString("payment_id")) || value.operationVersion() != row.getLong("operation_version")
                    || !value.basis().name().equals(row.getString("basis")) || !value.retiredBy().equals(row.getString("retired_by")) || !value.retiredAt().equals(instant(row.getTimestamp("retired_at")))) throw conflict();
            retirementProof(tenant, value); return value;
        }, tenant, id.toString()).stream().findFirst();
    }

    /** 恢复扫描排除已结束尝试；状态未知只能由领域领取原号查询。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT tenant_id,id FROM supplier_payable_settlement_operation WHERE retired_version IS NULL AND ((status IN ('QUEUED','UNKNOWN') AND next_attempt_at<=?)
                OR (status IN ('CHECKING','SETTLING','QUERYING') AND lease_until<=?)) ORDER BY COALESCE(next_attempt_at,lease_until),created_at,id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now), timestamp(now));
    }
    private SupplierPayableSettlementOperation restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), SupplierPayableSettlementOperation.class); var command = value.command();
        if (!command.equals(json.read(row.getString("command_json"), SupplierPayableSettlementCommand.class)) || !command.digest().equals(row.getString("command_digest"))
                || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                || !command.payment().id().toString().equals(row.getString("payment_id")) || command.paymentVersion() != row.getLong("payment_version")
                || !command.payment().holdCommand().authorization().source().reservation().id().toString().equals(row.getString("reservation_id"))
                || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                || value.dispatches() != row.getInt("dispatches") || value.highestRevision() != row.getLong("highest_revision")
                || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw inconsistent();
        var preparation = preparations.revision(command.tenantId(), command.id(), row.getLong("preparation_version")).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
        var bank = payments.revision(command.tenantId(), command.payment().id(), command.paymentVersion()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
        if (!bank.equals(preparation.input().payment()) || !command.registeredFrom(bank)
                || preparation.ready(command, value.createdAt()).status() != SupplierSettlementPreparation.Status.READY) throw conflict();
        Long retiredVersion = row.getObject("retired_version", Long.class);
        if (!Objects.equals(retiredVersion == null ? command.payment().id().toString() : null, row.getString("active_payment_id"))) throw inconsistent();
        if (retiredVersion != null) {
            var decision = retirement(command.tenantId(), command.id()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
            if (decision.operationVersion() != retiredVersion || !decision.matches(value)) throw conflict();
        }
        return value;
    }
    private SupplierPayableSettlementOperation retirementProof(String tenant, SupplierSettlementRetirement decision) {
        var proof = revision(tenant, decision.operationId(), decision.operationVersion()).orElseThrow(JdbcSupplierPayableSettlementRepository::conflict);
        if (!decision.matches(proof)) throw conflict(); return proof;
    }
    private void append(SupplierPayableSettlementOperation value) {
        jdbc.update("INSERT INTO supplier_payable_settlement_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)", value.command().tenantId(), value.command().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static IllegalStateException inconsistent() { return new IllegalStateException("Persisted supplier payable settlement is inconsistent"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original supplier settlement or persisted source changed"); }

    /**
     * 后台扫描只返回结算身份。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
