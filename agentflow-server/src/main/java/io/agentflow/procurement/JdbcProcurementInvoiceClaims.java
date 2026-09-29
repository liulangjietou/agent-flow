package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原采购应付与报销共用租户票号唯一键；付款申请释放不能撤销已经存在的采购入账事实。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcProcurementInvoiceClaims {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    /** 与原应付占用共用事务，唯一键负责仲裁两个业务同时首次提交的竞争。 */
    public JdbcProcurementInvoiceClaims(JdbcTemplate jdbc) { this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); }

    /** 只读预检和 READY 可用性复查；最终仍由登记时的数据库唯一约束兜底。 */
    public void requireAvailable(String tenant, ProcurementPaymentRound round) {
        for (var origin : origins(tenant, keys(round)).values()) origin.requireSamePayable(round.content());
    }

    /** 首次登记固定原应付证据，重提或同应付后续付款复用原记录，不换首次来源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recognize(ProcurementPayableReservation reservation) {
        var source = reservation.source(); var round = source.round(); var keys = keys(round); var existing = origins(source.tenantId(), keys);
        for (var key : keys) {
            var origin = existing.get(key);
            if (origin != null) { origin.requireSamePayable(round.content()); continue; }
            try {
                jdbc.update("""
                        INSERT INTO invoice_active_claim(tenant_id,invoice_key,source_type,procurement_reservation_id,round_no,status)
                        VALUES(?,?,'PROCUREMENT',?,?,'CONSUMED')
                        """, source.tenantId(), key, reservation.id().toString(), round.roundNo());
            } catch (DuplicateKeyException occupied) { throw occupied(); }
        }
    }

    private Map<String, Origin> origins(String tenant, List<String> keys) {
        return named.query("""
                SELECT c.invoice_key,c.source_type,r.legal_entity_id,r.supplier_reference,r.payable_reference
                FROM invoice_active_claim c LEFT JOIN procurement_payable_reservation r
                ON r.tenant_id=c.tenant_id AND r.id=c.procurement_reservation_id
                WHERE c.tenant_id=:tenant AND c.invoice_key IN (:keys)
                """, Map.of("tenant", tenant, "keys", keys), (row, index) -> new Origin(row.getString("invoice_key"), row.getString("source_type"),
                row.getString("legal_entity_id"), row.getString("supplier_reference"), row.getString("payable_reference")))
                .stream().collect(Collectors.toUnmodifiableMap(Origin::key, Function.identity()));
    }
    private static List<String> keys(ProcurementPaymentRound round) {
        return round.payable().lines().stream().map(line -> line.invoice().canonical()).distinct().sorted().toList();
    }
    private static DomainException occupied() { return new DomainException("INVOICE_OCCUPIED", "Invoice is already occupied or recognized by another financial source"); }

    /**
     * 来源保持原应付身份，当前申请人、付款金额和轮次变化均不能把同票号换到另一笔应付。
     * @author owlzhangfq@gmail.com
     */
    private record Origin(String key, String type, String legalEntity, String supplier, String payable) {
        private void requireSamePayable(ProcurementPaymentContent content) {
            if (!"PROCUREMENT".equals(type) || !content.legalEntityId().toString().equals(legalEntity)
                    || !content.supplierReference().equals(supplier) || !content.payableReference().equals(payable)) throw occupied();
        }
    }
}
