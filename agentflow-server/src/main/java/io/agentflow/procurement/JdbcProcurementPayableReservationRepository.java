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
 * 唯一键仲裁同一供应商应付的本地占用；原占用、释放和修订与业务事务一起提交。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcProcurementPayableReservationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ProcurementPaymentRepository requests;
    private final JdbcProcurementInvoiceClaims invoices;
    private final SupplierPayableReturnGuard returns;
    private final JdbcSupplierAdjustmentCompletions adjustments;

    /** 占用引用实际冻结版本，不能由页面自行填写余额或原应付事实。 */
    public JdbcProcurementPayableReservationRepository(JdbcTemplate jdbc, JsonUtil json, ProcurementPaymentRepository requests, JdbcProcurementInvoiceClaims invoices, SupplierPayableReturnGuard returns,
            JdbcSupplierAdjustmentCompletions adjustments) {
        this.jdbc = jdbc; this.json = json; this.requests = requests; this.invoices = invoices; this.returns = returns; this.adjustments = adjustments;
    }

    /** 唯一冲突拒绝第二张申请；不会覆盖原占用或把较小金额当作可并行承诺。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ProcurementPayableReservation value) {
        var source = value.source(); var current = requests.find(source.tenantId(), source.requestId()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        if (!value.held() || !ProcurementPayableReservation.hold(value.id(), current, value.heldAt()).equals(value)) throw conflict();
        var content = source.round().content();
        returns.lock(source.tenantId(), content); returns.requireClear(source.tenantId(), content);
        try {
            jdbc.update("""
                    INSERT INTO procurement_payable_reservation(tenant_id,id,request_id,application_id,employee_id,request_version,
                    round_no,legal_entity_id,supplier_reference,payable_reference,active_request_id,active_payable_reference,version,state_json,held_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                    """, source.tenantId(), value.id().toString(), source.requestId().toString(), source.applicationId().toString(), source.employeeId(), source.requestVersion(),
                    source.round().roundNo(), content.legalEntityId().toString(), content.supplierReference(), content.payableReference(), source.requestId().toString(),
                    content.payableReference(), json.write(value), Timestamp.from(value.heldAt()));
        } catch (DuplicateKeyException occupied) {
            throw new DomainException("PROCUREMENT_PAYABLE_OCCUPIED", "Original payable already has an active local payment request");
        }
        invoices.recognize(value);
        append(value);
    }

    /** 只保存从现行占用产生的精确释放，不允许换来源或复用已释放行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(ProcurementPayableReservation value) {
        if (value.held() || value.release() == null) throw conflict();
        var before = find(value.source().tenantId(), value.id()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        var decision = value.release();
        if (!before.release(decision.reason(), decision.releasedBy(), decision.releasedAt()).equals(value)) throw conflict();
        int changed = jdbc.update("""
                UPDATE procurement_payable_reservation SET version=2,state_json=?,released_at=?,active_request_id=NULL,active_payable_reference=NULL
                WHERE tenant_id=? AND id=? AND version=1
                """, json.write(value), Timestamp.from(decision.releasedAt()), value.source().tenantId(), value.id().toString());
        if (changed != 1) throw conflict();
        append(value);
    }

    /** 实际 ERP 终态与当前无争议银行修订一起核对，再原子保存完成依据和本地占用第二版。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProcurementPayableReservation complete(SupplierPayableSettlementOperation operation, SupplierPaymentOperation bank, Instant now) {
        var command = operation.command(); var original = command.payment().holdCommand().authorization().source().reservation(); var tenant = command.tenantId();
        requests.lock(tenant, original.source().requestId());
        returns.lock(tenant, original.source().round().content()); returns.requireClear(tenant, original.source().round().content());
        var before = find(tenant, original.id()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        if (!matchesBank(operation, bank, now) || !original.equals(before)) throw conflict();
        var saved = jdbc.query("""
                SELECT o.state_json AS settlement_json,b.state_json AS bank_json FROM supplier_payable_settlement_operation o
                JOIN supplier_payment_operation b ON b.tenant_id=o.tenant_id AND b.id=o.payment_id
                JOIN supplier_payable_settlement_revision r ON r.tenant_id=o.tenant_id AND r.operation_id=o.id AND r.version=o.version
                JOIN supplier_payment_revision p ON p.tenant_id=b.tenant_id AND p.operation_id=b.id AND p.version=b.version
                WHERE o.tenant_id=? AND o.id=? AND o.version=? AND o.status='SETTLED' AND o.retired_version IS NULL AND o.active_payment_id=b.id
                AND o.command_json=? AND o.command_digest=? AND b.version=? AND b.status='SUCCEEDED' AND b.command_json=? AND b.command_digest=?
                AND r.state_json=o.state_json AND p.state_json=b.state_json
                """, (row, index) -> operation.equals(json.read(row.getString("settlement_json"), SupplierPayableSettlementOperation.class))
                        && bank.equals(json.read(row.getString("bank_json"), SupplierPaymentOperation.class)), tenant, command.id().toString(), operation.version(),
                json.write(command), command.digest(), bank.version(), json.write(bank.command()), bank.command().digest());
        if (saved.size() != 1 || !saved.get(0)) throw conflict();
        var value = before.settle(operation, now);
        jdbc.update("INSERT INTO supplier_settlement_completion(tenant_id,reservation_id,operation_id,operation_version,payment_id,payment_version,completed_at) VALUES(?,?,?,?,?,?,?)",
                tenant, before.id().toString(), command.id().toString(), operation.version(), bank.command().id().toString(), bank.version(), Timestamp.from(now));
        int changed = jdbc.update("""
                UPDATE procurement_payable_reservation SET version=2,state_json=?,settled_at=?,settlement_id=?,settlement_version=?,active_request_id=NULL,active_payable_reference=NULL
                WHERE tenant_id=? AND id=? AND version=1
                """, json.write(value), Timestamp.from(now), command.id().toString(), operation.version(), tenant, value.id().toString());
        if (changed != 1) throw conflict(); append(value); return value;
    }

    /** 独立调整只结束仍保留的原占用；已有核销或前次调整的本地完成原件保持不变。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProcurementPayableReservation completeAdjustment(SupplierAdjustmentCompletion proof) {
        var command = proof.operation().command(); var source = command.source(); var tenant = command.tenantId();
        var original = proof.bank().command().holdCommand().authorization().source().reservation();
        requests.lock(tenant, original.source().requestId()); returns.lock(tenant, original.source().round().content());
        if (adjustments.find(tenant, command.id()).filter(proof::equals).isEmpty()) throw conflict();
        var before = find(tenant, original.id()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        if (!before.source().equals(original.source()) || before.release() != null) throw conflict();
        if (!before.held()) {
            if (source.recognizesOriginalPayment() || before.settlement() != null && (source.settlement() == null
                    || !before.settlement().operationId().equals(source.settlement().command().id()))
                    || before.adjustment() != null && source.previous() == null) throw conflict();
            return before;
        }
        // 原 ERP 核销成功但本地未结束时，调整仍可结束原占用，不重复确认原付款。
        if (!original.equals(before) || source.previous() != null) throw conflict();
        var value = before.adjust(proof.operation(), proof.completedAt());
        int changed = jdbc.update("""
                UPDATE procurement_payable_reservation SET version=2,state_json=?,adjusted_at=?,adjustment_id=?,adjustment_version=?,
                    active_request_id=NULL,active_payable_reference=NULL
                WHERE tenant_id=? AND id=? AND version=1 AND state_json=?
                """, json.write(value), Timestamp.from(proof.completedAt().truncatedTo(ChronoUnit.MICROS)), command.id().toString(), proof.operation().version(),
                tenant, original.id().toString(), json.write(before));
        if (changed != 1) throw conflict(); append(value); return value;
    }

    /** 当前申请只能有一笔未释放占用，保留中的旧轮次也必须先显式处理。 */
    public Optional<ProcurementPayableReservation> active(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM procurement_payable_reservation WHERE tenant_id=? AND active_request_id=?", this::restore,
                tenant, requestId.toString()).stream().findFirst();
    }

    /** 历史按原提交轮次返回，释放并不删除旧单据证据。 */
    public List<ProcurementPayableReservation> history(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM procurement_payable_reservation WHERE tenant_id=? AND request_id=? ORDER BY round_no", this::restore, tenant, requestId.toString());
    }

    /** 按原占用身份恢复完成凭据，后续新轮次不能替换旧付款引用的占用。 */
    public Optional<ProcurementPayableReservation> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM procurement_payable_reservation WHERE tenant_id=? AND id=?", this::restore, tenant, id.toString()).stream().findFirst();
    }
    private ProcurementPayableReservation restore(ResultSet row, int index) throws SQLException {
        var value = json.read(row.getString("state_json"), ProcurementPayableReservation.class); var source = value.source(); var content = source.round().content();
        if (!source.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                || !source.requestId().toString().equals(row.getString("request_id")) || !source.applicationId().toString().equals(row.getString("application_id"))
                || !source.employeeId().equals(row.getString("employee_id")) || source.requestVersion() != row.getLong("request_version")
                || source.round().roundNo() != row.getInt("round_no") || !content.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                || !content.supplierReference().equals(row.getString("supplier_reference")) || !content.payableReference().equals(row.getString("payable_reference"))
                || value.version() != row.getLong("version") || !value.heldAt().equals(row.getTimestamp("held_at").toInstant())
                || !Objects.equals(value.held() ? source.requestId().toString() : null, row.getString("active_request_id"))
                || !Objects.equals(value.held() ? content.payableReference() : null, row.getString("active_payable_reference"))
                || !Objects.equals(value.release() == null ? null : value.release().releasedAt(), instant(row.getTimestamp("released_at")))) {
            throw new IllegalStateException("Persisted procurement payable reservation binding is inconsistent");
        }
        if (value.settlement() != null) {
            var proof = value.settlement();
            if (!proof.completedAt().equals(instant(row.getTimestamp("settled_at"))) || !proof.operationId().toString().equals(row.getString("settlement_id"))
                    || proof.operationVersion() != row.getLong("settlement_version")) throw conflict();
            requireCompletion(value);
        }
        requireAdjustment(row, value);
        return value;
    }
    private void requireAdjustment(ResultSet row, ProcurementPayableReservation value) throws SQLException {
        var reference = value.adjustment();
        // 历史迁移夹具仍读取 V81 之前的表，旧结构只允许恢复没有调整引用的占用。
        var metadata = row.getMetaData(); boolean present = false;
        for (int index = 1; index <= metadata.getColumnCount(); index++) if ("adjustment_id".equalsIgnoreCase(metadata.getColumnLabel(index))) present = true;
        if (!present && reference == null) return;
        if (!Objects.equals(reference == null ? null : reference.operationId().toString(), row.getString("adjustment_id"))
                || !Objects.equals(reference == null ? null : reference.operationVersion(), row.getObject("adjustment_version", Long.class))
                || !Objects.equals(reference == null ? null : reference.completedAt().truncatedTo(ChronoUnit.MICROS), instant(row.getTimestamp("adjusted_at")))) throw conflict();
        if (reference == null) return;
        var proof = adjustments.find(value.source().tenantId(), reference.operationId()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        var original = proof.bank().command().holdCommand().authorization().source().reservation();
        if (!original.adjust(proof.operation(), proof.completedAt()).equals(value)) throw conflict();
    }
    private void requireCompletion(ProcurementPayableReservation value) {
        var proof = value.settlement();
        var evidence = jdbc.query("""
                SELECT c.*,o.state_json AS settlement_json,b.state_json AS bank_json FROM supplier_settlement_completion c
                JOIN supplier_payable_settlement_revision o ON o.tenant_id=c.tenant_id AND o.operation_id=c.operation_id AND o.version=c.operation_version
                JOIN supplier_payment_revision b ON b.tenant_id=c.tenant_id AND b.operation_id=c.payment_id AND b.version=c.payment_version
                WHERE c.tenant_id=? AND c.reservation_id=?
                """, (row, index) -> {
                    var operation = json.read(row.getString("settlement_json"), SupplierPayableSettlementOperation.class);
                    var bank = json.read(row.getString("bank_json"), SupplierPaymentOperation.class);
                    return proof.operationId().toString().equals(row.getString("operation_id")) && proof.operationVersion() == row.getLong("operation_version")
                            && proof.paymentId().toString().equals(row.getString("payment_id")) && bank.version() == row.getLong("payment_version")
                            && proof.completedAt().equals(instant(row.getTimestamp("completed_at"))) && matchesBank(operation, bank, proof.completedAt())
                            && operation.command().payment().holdCommand().authorization().source().reservation().settle(operation, proof.completedAt()).equals(value);
                }, value.source().tenantId(), value.id().toString());
        if (evidence.size() != 1 || !evidence.get(0)) throw conflict();
    }
    private static boolean matchesBank(SupplierPayableSettlementOperation operation, SupplierPaymentOperation bank, Instant completedAt) {
        if (!operation.settled() || bank == null || !bank.settleable() || !bank.command().equals(operation.command().payment())
                || bank.version() < operation.command().paymentVersion() || completedAt == null || completedAt.isBefore(bank.updatedAt())) return false;
        var registered = operation.command().paid(); var current = bank.observation();
        return registered.paymentReference().equals(current.paymentReference()) && registered.paidAmount().equals(current.paidAmount())
                && registered.accountDigest().equals(current.accountDigest()) && registered.completedAt().equals(current.completedAt()) && registered.receiptReference().equals(current.receiptReference());
    }
    private void append(ProcurementPayableReservation value) {
        jdbc.update("INSERT INTO procurement_payable_reservation_revision(tenant_id,reservation_id,version,state_json) VALUES(?,?,?,?)",
                value.source().tenantId(), value.id().toString(), value.version(), json.write(value));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Procurement payable reservation source or version changed"); }
}
