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
import java.util.Optional;
import java.util.UUID;

/**
 * 原放款决定关联相邻借款修订及被消费查询，新增入款同时进入跨入口防重账本。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcDisbursementResolutionRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcDisbursementReturnCheckRepository checks;
    private final JdbcAdvanceReceiptCreditRepository credits;
    /** 全部持久事实由同一借款事务提交。 */
    public JdbcDisbursementResolutionRepository(JdbcTemplate jdbc, JsonUtil json, JdbcDisbursementReturnCheckRepository checks, JdbcAdvanceReceiptCreditRepository credits) {
        this.jdbc = jdbc; this.json = json; this.checks = checks; this.credits = credits;
    }
    /** 重放本次状态变化，不能保存与余额不一致的人工决定或单独的防重记录。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceDisbursementReturn decision, EmployeeAdvance after) {
        var check = checks.find(decision.tenantId(), decision.checkId()).orElseThrow(JdbcDisbursementResolutionRepository::changed);
        if (check.status() != AdvanceDisbursementReturnCheck.Status.RESOLVED || !decision.id().equals(check.resolutionId()) || !decision.receipt().equals(check.receipt())
                || !decision.resolvedBy().equals(check.input().requestedBy()) || !decision.resolvedAt().equals(check.updatedAt())) throw changed();
        var before = revision(after.tenantId(), after.id(), after.version() - 1); before.resolveDisbursementReview(before.version(), decision);
        if (!before.state().equals(after.state()) || !revision(after.tenantId(), after.id(), after.version()).state().equals(after.state())) throw changed();
        jdbc.update("""
                INSERT INTO advance_disbursement_resolution(tenant_id,id,advance_id,advance_version,check_id,check_version,outcome,resolved_by,observed_at,resolved_at,state_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, decision.tenantId(), decision.id().toString(), after.id().toString(), after.version(), decision.checkId().toString(), check.version(),
                decision.receipt().status().name(), decision.resolvedBy(), Timestamp.from(decision.receipt().observedAt()), Timestamp.from(decision.resolvedAt()), json.write(decision));
        try {
            for (var entry : after.disbursementReturns()) if (entry.resolutionId().equals(decision.id())) credits.record(decision, entry);
        } catch (DuplicateKeyException duplicate) { throw new DomainException("DISBURSEMENT_RETURN_ALREADY_RECORDED", "Received funds or credit already belongs to another repayment, disbursement adjustment or expense return"); }
    }
    /** 最近人工决定用于展示和约束后续查询，读取不修改原件或续期。 */
    public Optional<AdvanceDisbursementReturn> latest(String tenant, UUID advanceId) {
        return jdbc.query("SELECT * FROM advance_disbursement_resolution WHERE tenant_id=? AND advance_id=? ORDER BY advance_version DESC LIMIT 1", row(), tenant, advanceId.toString()).stream().findFirst();
    }
    private EmployeeAdvance revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM finance_resource_revision WHERE tenant_id=? AND resource_type='ADVANCE' AND resource_id=? AND version=?",
                (row, index) -> EmployeeAdvance.restore(json.read(row.getString("state_json"), EmployeeAdvance.State.class)), tenant, id.toString(), version).stream().findFirst().orElseThrow(JdbcDisbursementResolutionRepository::changed);
    }
    private RowMapper<AdvanceDisbursementReturn> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceDisbursementReturn.class); var command = value.receipt().request().command();
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                    || !command.binding().businessId().toString().equals(row.getString("advance_id")) || !value.checkId().toString().equals(row.getString("check_id"))
                    || !value.receipt().status().name().equals(row.getString("outcome")) || !value.resolvedBy().equals(row.getString("resolved_by"))
                    || !value.receipt().observedAt().equals(row.getTimestamp("observed_at").toInstant()) || !value.resolvedAt().equals(row.getTimestamp("resolved_at").toInstant())) throw new IllegalStateException("Persisted disbursement resolution identity is inconsistent");
            return value;
        };
    }
    private static DomainException changed() { return new DomainException("DISBURSEMENT_RETURN_SOURCE_CHANGED", "Disbursement decision must match its consumed check and both advance revisions"); }
}
