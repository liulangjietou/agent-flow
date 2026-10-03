package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 还款只读队列保留不可变输入及每次修订，所有写入加入原业务事务。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcAdvanceRepaymentCheckRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 复用平台事务及 JSON，不在仓储执行外部查询。 */
    public JdbcAdvanceRepaymentCheckRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    /** 首次请求先保存，实际读取只能消费数据库中可见的队列。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(AdvanceRepaymentCheck value) {
        if (value.version() != 1 || value.status() != AdvanceRepaymentCheck.Status.QUEUED) throw conflict();
        var input = value.input();
        jdbc.update("""
                INSERT INTO advance_repayment_check(tenant_id,id,advance_id,payment_id,payment_version,requested_by,receipt_reference,input_json,state_json,version,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,1,'QUEUED',?,?)
                """, input.tenantId(), input.id().toString(), input.request().advanceId().toString(), input.paymentId().toString(), input.paymentVersion(),
                input.requestedBy(), input.request().receiptReference(), json.write(input), json.write(value), timestamp(input.requestedAt()), timestamp(value.updatedAt()));
        append(value);
    }
    /** 原输入及版本比较拒绝迟到完成、重复确认和任务串换。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(AdvanceRepaymentCheck value) {
        var input = value.input();
        int count = jdbc.update("UPDATE advance_repayment_check SET version=?,status=?,state_json=?,updated_at=?,lease_until=? WHERE tenant_id=? AND id=? AND version=? AND input_json=?",
                value.version(), value.status().name(), json.write(value), timestamp(value.updatedAt()), timestamp(value.leaseUntil()), input.tenantId(), input.id().toString(), value.version() - 1, json.write(input));
        if (count != 1) throw conflict(); append(value);
    }
    /** 按认证或队列租户定位，不从任务编号推断权限。 */
    public Optional<AdvanceRepaymentCheck> find(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM advance_repayment_check WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst();
    }
    /** 每位财务只使用自己最新明确读取的凭据。 */
    public Optional<AdvanceRepaymentCheck> latest(String tenant, UUID advanceId, String actor) {
        return jdbc.query("SELECT * FROM advance_repayment_check WHERE tenant_id=? AND advance_id=? AND requested_by=? ORDER BY created_at DESC,id DESC LIMIT 1",
                row(), tenant, advanceId.toString(), actor).stream().findFirst();
    }
    /** 同一原凭据的已完成读取用于跨操作者拒绝旧事实，失败的传输不成为资金结论。 */
    public List<AdvanceRepaymentCheck> receiptHistory(String tenant, UUID advanceId, String reference) {
        return jdbc.query("SELECT * FROM advance_repayment_check WHERE tenant_id=? AND advance_id=? AND receipt_reference=? AND status IN ('CHECKED','RECORDED') ORDER BY updated_at DESC,id DESC",
                row(), tenant, advanceId.toString(), reference);
    }
    /** 租约超时也进入有界扫描，由应用服务标记失败。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id FROM advance_repayment_check WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<=?) ORDER BY created_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id"))), timestamp(now));
    }
    private RowMapper<AdvanceRepaymentCheck> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), AdvanceRepaymentCheck.class); var input = value.input();
            if (!input.tenantId().equals(row.getString("tenant_id")) || !input.id().toString().equals(row.getString("id"))
                    || !input.request().advanceId().toString().equals(row.getString("advance_id")) || !input.paymentId().toString().equals(row.getString("payment_id"))
                    || input.paymentVersion() != row.getLong("payment_version") || !input.requestedBy().equals(row.getString("requested_by"))
                    || !input.request().receiptReference().equals(row.getString("receipt_reference"))
                    || !input.equals(json.read(row.getString("input_json"), AdvanceRepaymentCheck.Input.class)) || value.version() != row.getLong("version")
                    || !value.status().name().equals(row.getString("status")) || !input.requestedAt().equals(instant(row.getTimestamp("created_at")))
                    || !value.updatedAt().equals(instant(row.getTimestamp("updated_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw new IllegalStateException("Persisted repayment check identity is inconsistent");
            return value;
        };
    }
    private void append(AdvanceRepaymentCheck value) {
        jdbc.update("INSERT INTO advance_repayment_check_revision(tenant_id,check_id,version,state_json) VALUES(?,?,?,?)", value.input().tenantId(), value.input().id().toString(), value.version(), json.write(value));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Repayment check input or version changed"); }
    /**
     * 扫描仅返回身份，原件按租约领取后再加载。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id) { }
}
