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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 每次还款决定只追加，真实退回以原还款、资金流水和 ERP 借方分录分别防重。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcRepaymentResolutionRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcRepaymentReviewCheckRepository checks;
    /** 裁决必须对应已消费查询及借款精确的连续版本。 */
    public JdbcRepaymentResolutionRepository(JdbcTemplate jdbc, JsonUtil json, JdbcRepaymentReviewCheckRepository checks) { this.jdbc = jdbc; this.json = json; this.checks = checks; }

    /** 与借款余额、查询消费及审计同事务保存，失败不能留下部分调整。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepaymentResolution decision, EmployeeAdvance after) {
        var check = checks.find(decision.tenantId(), decision.checkId()).orElseThrow(JdbcRepaymentResolutionRepository::changed);
        if (check.status() != AdvanceRepaymentReviewCheck.Status.RESOLVED || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                || !decision.resolvedBy().equals(check.input().requestedBy()) || !decision.resolvedAt().equals(check.updatedAt())) throw changed();
        var before = revision(after.tenantId(), after.id(), after.version() - 1);
        before.resolveRepaymentReview(before.version(), decision);
        if (!before.state().equals(after.state()) || !revision(after.tenantId(), after.id(), after.version()).state().equals(after.state())) throw changed();
        jdbc.update("""
                INSERT INTO advance_repayment_resolution(tenant_id,id,advance_id,advance_version,repayment_id,check_id,check_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, decision.tenantId(), decision.id().toString(), after.id().toString(), after.version(), decision.receipt().request().repaymentId().toString(), decision.checkId().toString(), check.version(),
                decision.receipt().status().name(), decision.resolvedBy(), timestamp(decision.receipt().observedAt()), timestamp(decision.resolvedAt()), json.write(decision));
        for (var entry : after.repaymentReturns().stream().filter(value -> value.resolutionId().equals(decision.id())).toList()) {
            var receipt = decision.receipt(); var source = receipt.request().original().request();
            try {
                jdbc.update("""
                        INSERT INTO advance_repayment_return(tenant_id,repayment_id,resolution_id,legal_entity_id,channel,transaction_reference,voucher_reference,entry_reference,amount,currency)
                        VALUES(?,?,?,?,?,?,?,?,?,?)
                        """, decision.tenantId(), receipt.request().repaymentId().toString(), decision.id().toString(), source.legalEntityId().toString(), entry.fundsReturn().channel().name(),
                        entry.fundsReturn().transactionReference(), entry.posting().voucherReference(), entry.posting().entryReference(), entry.amount().value(), entry.amount().currency());
            } catch (DuplicateKeyException duplicate) { throw new DomainException("REPAYMENT_RETURN_ALREADY_RECORDED", "Returned funds or accounting entry already belongs to a repayment adjustment"); }
        }
    }
    /** 最新决定供工作区展示，原决定及财务调整均不覆盖。 */
    public Optional<AdvanceRepaymentResolution> latest(String tenant, UUID repaymentId) {
        return jdbc.query("SELECT * FROM advance_repayment_resolution WHERE tenant_id=? AND repayment_id=? ORDER BY advance_version DESC LIMIT 1", row(), tenant, repaymentId.toString()).stream().findFirst();
    }
    /** 原退款事实后续只能复核，不会因为新查询再次加回欠款。 */
    public Optional<AdvanceRepaymentResolution> returned(String tenant, UUID repaymentId) {
        return jdbc.query("""
                SELECT d.*,r.legal_entity_id,r.channel,r.transaction_reference,r.voucher_reference,r.entry_reference,r.amount,r.currency
                FROM advance_repayment_return r JOIN advance_repayment_resolution d ON d.tenant_id=r.tenant_id AND d.id=r.resolution_id
                WHERE r.tenant_id=? AND r.repayment_id=? ORDER BY d.advance_version DESC,r.channel,r.transaction_reference
                """, (data, index) -> {
            var decision = row().mapRow(data, index); var receipt = decision.receipt();
            String channel = data.getString("channel"), reference = data.getString("transaction_reference");
            var item = receipt.returns().stream().filter(value -> value.fundsReturn().channel().name().equals(channel)
                    && value.fundsReturn().transactionReference().equals(reference)).findFirst().orElse(null);
            if (item == null || !receipt.request().repaymentId().equals(repaymentId)
                    || !receipt.request().original().request().legalEntityId().toString().equals(data.getString("legal_entity_id"))
                    || !item.posting().voucherReference().equals(data.getString("voucher_reference")) || !item.posting().entryReference().equals(data.getString("entry_reference"))
                    || item.fundsReturn().amount().value().compareTo(data.getBigDecimal("amount")) != 0 || !item.fundsReturn().amount().currency().equals(data.getString("currency"))) throw new IllegalStateException("Persisted repayment return identity is inconsistent");
            return decision;
        }, tenant, repaymentId.toString()).stream().findFirst();
    }
    /** 按原决定恢复被消费查询及相邻借款修订，最新余额不能替代历史裁决。 */
    public Optional<Resolved> find(String tenant, UUID id) {
        return jdbc.query("""
                SELECT r.*,p.state_json AS prior_check_json,c.state_json AS resolved_check_json
                FROM advance_repayment_resolution r
                JOIN repayment_review_check_revision p ON p.tenant_id=r.tenant_id AND p.check_id=r.check_id AND p.version=r.check_version-1
                JOIN repayment_review_check_revision c ON c.tenant_id=r.tenant_id AND c.check_id=r.check_id AND c.version=r.check_version
                WHERE r.tenant_id=? AND r.id=?
                """, (row, index) -> {
            var decision = row().mapRow(row, index); var advanceId = decision.receipt().request().original().request().advanceId();
            long version = row.getLong("advance_version"), checkVersion = row.getLong("check_version");
            var before = revision(tenant, advanceId, version - 1); var after = revision(tenant, advanceId, version);
            var prior = json.read(row.getString("prior_check_json"), AdvanceRepaymentReviewCheck.class);
            var resolved = json.read(row.getString("resolved_check_json"), AdvanceRepaymentReviewCheck.class);
            if (!decision.id().equals(id) || !decision.tenantId().equals(tenant) || before.version() != version - 1 || after.version() != version
                    || !before.tenantId().equals(tenant) || !after.tenantId().equals(tenant) || !before.id().equals(advanceId) || !after.id().equals(advanceId)
                    || prior.version() != checkVersion - 1 || resolved.version() != checkVersion || !prior.input().id().equals(decision.checkId())
                    || !prior.input().tenantId().equals(tenant) || !prior.resolve(decision, decision.resolvedAt()).equals(resolved)) throw changed();
            before.resolveRepaymentReview(before.version(), decision);
            if (!before.state().equals(after.state())) throw changed();
            return new Resolved(version, decision);
        }, tenant, id.toString()).stream().findFirst();
    }
    /**
     * 该次决定对应的借款修订，不提供当前可用额或办理许可。
     * @author owlzhangfq@gmail.com
     */
    public record Resolved(long advanceVersion, AdvanceRepaymentResolution decision) { }
    private EmployeeAdvance revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM finance_resource_revision WHERE tenant_id=? AND resource_type='ADVANCE' AND resource_id=? AND version=?",
                (row, index) -> EmployeeAdvance.restore(json.read(row.getString("state_json"), EmployeeAdvance.State.class)), tenant, id.toString(), version).stream().findFirst().orElseThrow(JdbcRepaymentResolutionRepository::changed);
    }
    private RowMapper<AdvanceRepaymentResolution> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceRepaymentResolution.class);
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id")) || !value.checkId().toString().equals(row.getString("check_id"))
                    || !value.receipt().request().repaymentId().toString().equals(row.getString("repayment_id")) || !value.receipt().request().original().request().advanceId().toString().equals(row.getString("advance_id"))
                    || !value.receipt().status().name().equals(row.getString("outcome")) || !value.resolvedBy().equals(row.getString("resolved_by"))
                    || !value.receipt().observedAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("observed_at").toInstant())
                    || !value.resolvedAt().truncatedTo(ChronoUnit.MICROS).equals(row.getTimestamp("resolved_at").toInstant())) throw new IllegalStateException("Persisted repayment decision identity is inconsistent");
            return value;
        };
    }
    private static Timestamp timestamp(Instant at) { return Timestamp.from(at.truncatedTo(ChronoUnit.MICROS)); }
    private static DomainException changed() { return new DomainException("ADVANCE_REPAYMENT_SOURCE_CHANGED", "Repayment resolution must match the consumed review and both advance revisions"); }
}
