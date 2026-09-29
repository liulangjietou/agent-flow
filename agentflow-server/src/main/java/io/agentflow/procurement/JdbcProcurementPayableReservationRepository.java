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
 * 唯一键仲裁同一供应商应付的本地占用；原占用、释放和修订与业务事务一起提交。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcProcurementPayableReservationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ProcurementPaymentRepository requests;
    private final JdbcProcurementInvoiceClaims invoices;

    /** 占用引用实际冻结版本，不能由页面自行填写余额或原应付事实。 */
    public JdbcProcurementPayableReservationRepository(JdbcTemplate jdbc, JsonUtil json, ProcurementPaymentRepository requests, JdbcProcurementInvoiceClaims invoices) {
        this.jdbc = jdbc; this.json = json; this.requests = requests; this.invoices = invoices;
    }

    /** 唯一冲突拒绝第二张申请；不会覆盖原占用或把较小金额当作可并行承诺。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(ProcurementPayableReservation value) {
        var source = value.source(); var current = requests.find(source.tenantId(), source.requestId()).orElseThrow(JdbcProcurementPayableReservationRepository::conflict);
        if (!value.held() || !ProcurementPayableReservation.hold(value.id(), current, value.heldAt()).equals(value)) throw conflict();
        var content = source.round().content();
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

    /** 当前申请只能有一笔未释放占用，保留中的旧轮次也必须先显式处理。 */
    public Optional<ProcurementPayableReservation> active(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM procurement_payable_reservation WHERE tenant_id=? AND active_request_id=?", this::restore,
                tenant, requestId.toString()).stream().findFirst();
    }

    /** 历史按原提交轮次返回，释放并不删除旧单据证据。 */
    public List<ProcurementPayableReservation> history(String tenant, UUID requestId) {
        return jdbc.query("SELECT * FROM procurement_payable_reservation WHERE tenant_id=? AND request_id=? ORDER BY round_no", this::restore, tenant, requestId.toString());
    }

    private Optional<ProcurementPayableReservation> find(String tenant, UUID id) {
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
        return value;
    }
    private void append(ProcurementPayableReservation value) {
        jdbc.update("INSERT INTO procurement_payable_reservation_revision(tenant_id,reservation_id,version,state_json) VALUES(?,?,?,?)",
                value.source().tenantId(), value.id().toString(), value.version(), json.write(value));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Procurement payable reservation source or version changed"); }
}
