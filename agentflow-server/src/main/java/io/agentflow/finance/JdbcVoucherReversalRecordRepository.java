package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * 真实冲销凭证、原凭证修订与被消费查询共同构成不可变登记，不生成会计或资金命令。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcVoucherReversalRecordRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcVoucherReversalCheckRepository checks;
    private final JdbcVoucherOperationRepository operations;
    /** 两张凭证身份与完整核验事实在原申请事务中保存。 */
    public JdbcVoucherReversalRecordRepository(JdbcTemplate jdbc, JsonUtil json, JdbcVoucherReversalCheckRepository checks, JdbcVoucherOperationRepository operations) {
        this.jdbc = jdbc; this.json = json; this.checks = checks; this.operations = operations;
    }
    /** 校验已消费查询和精确原修订，法人内同一反向过账不能重复登记。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(VoucherReversalRecord value) {
        var check = checks.find(value.tenantId(), value.checkId()).orElseThrow(JdbcVoucherReversalRecordRepository::changed);
        var operation = operations.revision(value.tenantId(), value.operationId(), value.operationVersion()).orElseThrow(JdbcVoucherReversalRecordRepository::changed);
        if (check.status() != VoucherReversalCheck.Status.RECORDED || !value.id().equals(check.recordId()) || !value.receipt().equals(check.receipt())
                || !value.recordedBy().equals(check.input().requestedBy()) || !value.recordedAt().equals(check.updatedAt())
                || operation.status() != VoucherOperation.Status.REVERSED || !value.receipt().matchesCurrent(operation.observation())
                || !operation.input().command().equals(value.receipt().request().command())
                || !operations.find(value.tenantId(), value.operationId()).filter(operation::equals).isPresent()) throw changed();
        var reversal = value.receipt().reversal();
        try {
            jdbc.update("""
                    INSERT INTO voucher_reversal_record(tenant_id,id,operation_id,operation_version,check_id,check_version,legal_entity_id,
                    reversal_posting_reference,reversal_voucher_reference,recorded_by,observed_at,recorded_at,state_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.tenantId(), value.id().toString(), value.operationId().toString(), value.operationVersion(), value.checkId().toString(), check.version(), value.legalEntityId().toString(),
                    reversal.postingReference(), reversal.voucherReference(), value.recordedBy(), Timestamp.from(value.receipt().observedAt()), Timestamp.from(value.recordedAt()), json.write(value));
        } catch (DuplicateKeyException duplicate) { throw new DomainException("VOUCHER_REVERSAL_ALREADY_RECORDED", "Original voucher or independent reverse posting already has a record"); }
    }
    /** 登记按原凭证唯一；读取不会续期原证据或重新执行财务动作。 */
    public Optional<VoucherReversalRecord> forOperation(String tenant, UUID operationId) {
        return jdbc.query("SELECT * FROM voucher_reversal_record WHERE tenant_id=? AND operation_id=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), VoucherReversalRecord.class); var reversal = value.receipt().reversal();
            if (!value.tenantId().equals(row.getString("tenant_id")) || !value.id().toString().equals(row.getString("id"))
                    || !value.operationId().toString().equals(row.getString("operation_id")) || value.operationVersion() != row.getLong("operation_version")
                    || !value.checkId().toString().equals(row.getString("check_id")) || !value.legalEntityId().toString().equals(row.getString("legal_entity_id"))
                    || !reversal.postingReference().equals(row.getString("reversal_posting_reference")) || !reversal.voucherReference().equals(row.getString("reversal_voucher_reference"))
                    || !value.recordedBy().equals(row.getString("recorded_by")) || !value.recordedAt().equals(row.getTimestamp("recorded_at").toInstant())
                    || !value.receipt().observedAt().equals(row.getTimestamp("observed_at").toInstant())) throw new IllegalStateException("Persisted voucher reversal record identity is inconsistent");
            return value;
        }, tenant, operationId.toString()).stream().findFirst();
    }
    private static DomainException changed() { return new DomainException("VOUCHER_REVERSAL_SOURCE_CHANGED", "Voucher reversal record must match its consumed check and current original revision"); }
}
