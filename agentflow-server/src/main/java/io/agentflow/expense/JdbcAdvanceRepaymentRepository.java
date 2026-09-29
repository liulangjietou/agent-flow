package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 已确认还款只追加，收款编号、资金流水和会计分录均按法人防重。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceRepaymentRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcAdvanceRepaymentCheckRepository checks;
    /** 关联原查询和借款连续修订，不保存没有余额归属的人工声明。 */
    public JdbcAdvanceRepaymentRepository(JdbcTemplate jdbc, JsonUtil json, JdbcAdvanceRepaymentCheckRepository checks) { this.jdbc = jdbc; this.json = json; this.checks = checks; }
    /** 原确认、借款余额、规范化防重键及审计必须一起提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepayment value, EmployeeAdvance after) {
        var check = checks.find(value.tenantId(), value.checkId()).orElseThrow(JdbcAdvanceRepaymentRepository::changed);
        if (check.status() != AdvanceRepaymentCheck.Status.RECORDED || !value.id().equals(check.repaymentId()) || !value.receipt().equals(check.receipt())
                || !value.recordedBy().equals(check.input().requestedBy()) || !value.recordedAt().equals(check.updatedAt())) throw changed();
        var previous = revision(after.tenantId(), after.id(), after.version() - 1); previous.repay(previous.version(), value);
        if (!previous.state().equals(after.state()) || !revision(after.tenantId(), after.id(), after.version()).state().equals(after.state())) throw changed();
        var receipt = value.receipt(); var request = receipt.request();
        try {
            jdbc.update("""
                    INSERT INTO advance_repayment(tenant_id,id,advance_id,advance_version,check_id,check_version,legal_entity_id,receipt_reference,channel,
                    transaction_reference,voucher_reference,entry_reference,amount,currency,recorded_by,recorded_at,state_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.tenantId(), value.id().toString(), request.advanceId().toString(), after.version(), value.checkId().toString(), check.version(), request.legalEntityId().toString(),
                    request.receiptReference(), receipt.funding().channel().name(), receipt.funding().transactionReference(), receipt.posting().voucherReference(), receipt.posting().entryReference(),
                    value.amount().value(), value.amount().currency(), value.recordedBy(), Timestamp.from(value.recordedAt()), json.write(value));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("ADVANCE_REPAYMENT_ALREADY_RECORDED", "Receipt, received funds or accounting entry has already been recorded"); }
    }
    /** 同法人外部收款编号唯一，不因更换借款或查询编号再次入账。 */
    public Optional<AdvanceRepayment> forReceipt(String tenant, UUID legalEntity, String reference) {
        return jdbc.query("SELECT * FROM advance_repayment WHERE tenant_id=? AND legal_entity_id=? AND receipt_reference=?", row(), tenant, legalEntity.toString(), reference).stream().findFirst();
    }
    /** 有界历史按发生时间和编号稳定翻页；游标必须属于同一借款。 */
    public List<AdvanceRepayment> list(String tenant, UUID advanceId, UUID before, int limit) {
        var args = new java.util.ArrayList<Object>(List.of(tenant, advanceId.toString())); String filter = "";
        if (before != null) {
            var dates = jdbc.queryForList("SELECT recorded_at FROM advance_repayment WHERE tenant_id=? AND advance_id=? AND id=?", Timestamp.class, tenant, advanceId.toString(), before.toString());
            if (dates.isEmpty()) throw new DomainException("INVALID_ADVANCE_REPAYMENT_QUERY", "Repayment cursor must belong to this advance");
            filter = " AND (recorded_at<? OR (recorded_at=? AND id<?))"; args.add(dates.get(0)); args.add(dates.get(0)); args.add(before.toString());
        }
        args.add(limit);
        return jdbc.query("SELECT * FROM advance_repayment WHERE tenant_id=? AND advance_id=?" + filter + " ORDER BY recorded_at DESC,id DESC LIMIT ?", row(), args.toArray());
    }
    private EmployeeAdvance revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM finance_resource_revision WHERE tenant_id=? AND resource_type='ADVANCE' AND resource_id=? AND version=?",
                (row, index) -> EmployeeAdvance.restore(json.read(row.getString("state_json"), EmployeeAdvance.State.class)), tenant, id.toString(), version).stream().findFirst().orElseThrow(JdbcAdvanceRepaymentRepository::changed);
    }
    private RowMapper<AdvanceRepayment> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceRepayment.class); var receipt = value.receipt(); var source = receipt.request();
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                    || !source.advanceId().toString().equals(row.getString("advance_id")) || !value.checkId().toString().equals(row.getString("check_id"))
                    || !source.legalEntityId().toString().equals(row.getString("legal_entity_id")) || !source.receiptReference().equals(row.getString("receipt_reference"))
                    || !receipt.funding().channel().name().equals(row.getString("channel")) || !receipt.funding().transactionReference().equals(row.getString("transaction_reference"))
                    || !receipt.posting().voucherReference().equals(row.getString("voucher_reference")) || !receipt.posting().entryReference().equals(row.getString("entry_reference"))
                    || value.amount().value().compareTo(row.getBigDecimal("amount")) != 0 || !value.amount().currency().equals(row.getString("currency"))
                    || !value.recordedBy().equals(row.getString("recorded_by")) || !value.recordedAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("recorded_at").toInstant())) throw new IllegalStateException("Persisted repayment identity is inconsistent");
            return value;
        };
    }
    private static DomainException changed() { return new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "Recorded repayment must match the consumed check and both original advance revisions"); }
}
