package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.observability.DiagnosticContext;
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
 * 原授权唯一付款执行及其恢复版本，固定命令摘要、财务目标和出款账户。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentOperationRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final JdbcPaymentAuthorizationRepository authorizations;
    /** 授权与执行同事务核验，不在仓储内访问资金系统。 */
    public JdbcPaymentOperationRepository(JdbcTemplate jdbc, JsonUtil json, JdbcPaymentAuthorizationRepository authorizations) { this.jdbc = jdbc; this.json = json; this.authorizations = authorizations; }

    /** 同一事务中必须已经保存出纳执行登记，执行命令与该登记完全相同。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentOperation value) {
        if (value.version() != 1 || value.status() != PaymentOperation.Status.QUEUED || value.attempts() != 0 || value.dispatches() != 0) throw conflict();
        var command = value.input().command(); var authorization = authorizations.find(command.tenantId(), command.id()).orElseThrow(JdbcPaymentOperationRepository::conflict);
        if (!PaymentOperation.queue(authorization, value.createdAt()).equals(value)) throw conflict();
        jdbc.update("""
                INSERT INTO payment_operation(tenant_id,id,input_json,command_digest,state_json,version,status,attempts,dispatches,highest_revision,created_at,updated_at,next_attempt_at,trace_id)
                VALUES(?,?,?,?,?,1,'QUEUED',0,0,0,?,?,?,?)
                """, command.tenantId(), command.id().toString(), json.write(value.input()), command.digest(), json.write(value),
                timestamp(value.createdAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), DiagnosticContext.capture().traceId());
        append(value);
    }

    /** 租约结果按原版本比较更新，迟到执行者不能覆盖新领取和到账结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentOperation value) {
        var command = value.input().command();
        int changed = jdbc.update("""
                UPDATE payment_operation SET version=?,status=?,attempts=?,dispatches=?,highest_revision=?,state_json=?,updated_at=?,next_attempt_at=?,lease_until=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND command_digest=?
                """, value.version(), value.status().name(), value.attempts(), value.dispatches(), value.highestRevision(), json.write(value), timestamp(value.updatedAt()),
                timestamp(value.nextAttemptAt()), timestamp(value.leaseUntil()), command.tenantId(), command.id().toString(), value.version() - 1, json.write(value.input()), command.digest());
        if (changed != 1) throw conflict(); append(value);
    }
    /** 按原授权读取，领域构造及关系列共同校验持久状态。 */
    public Optional<PaymentOperation> find(String tenant, UUID id) { return jdbc.query("SELECT * FROM payment_operation WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst(); }
    /** 付款凭证沿用已经保存的成功回单修订，重新查询不替换原会计依据。 */
    public Optional<PaymentOperation> revision(String tenant, UUID id, long version) {
        return jdbc.query("SELECT state_json FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? AND version=?", (row, index) -> {
            var value = json.read(row.getString("state_json"), PaymentOperation.class); var command = value.input().command();
            if (!command.tenantId().equals(tenant) || !command.id().equals(id) || value.version() != version) {
                throw new IllegalStateException("Persisted payment revision identity is inconsistent");
            }
            return value;
        }, tenant, id.toString(), version).stream().findFirst();
    }
    /** 原放款退回始终绑定首次真实成功修订，后续争议或账户变化不替换它。 */
    public Optional<PaymentOperation> firstSuccessfulRevision(String tenant, UUID id) {
        return jdbc.query("SELECT version,state_json FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", rows -> {
            while (rows.next()) {
                var value = json.read(rows.getString("state_json"), PaymentOperation.class); var command = value.input().command();
                if (!tenant.equals(command.tenantId()) || !id.equals(command.id()) || value.version() != rows.getLong("version")) throw new IllegalStateException("Persisted payment revision identity is inconsistent");
                if (value.settleable()) return Optional.of(value);
            }
            return Optional.empty();
        }, tenant, id.toString());
    }
    /** 争议裁决逐条核对历史修订，曾出现的到账或退回不能因后续回执覆盖而丢失。 */
    public DisputeEvidence disputeEvidence(String tenant, UUID id) {
        return jdbc.query("SELECT version,state_json FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", rows -> {
            PaymentObservation firstSuccess = null; boolean fundingObserved = false;
            while (rows.next()) {
                var value = json.read(rows.getString("state_json"), PaymentOperation.class); var command = value.input().command();
                if (!tenant.equals(command.tenantId()) || !id.equals(command.id()) || value.version() != rows.getLong("version")) throw new IllegalStateException("Persisted dispute evidence identity is inconsistent");
                if (firstSuccess == null && value.settleable()) firstSuccess = value.observation();
                fundingObserved |= funding(value.observation()) || funding(value.conflictingObservation());
            }
            return new DisputeEvidence(firstSuccess, fundingObserved);
        }, tenant, id.toString());
    }
    private static boolean funding(PaymentObservation value) { return value != null && (value.status() == PaymentObservation.Status.SUCCEEDED || value.status() == PaymentObservation.Status.REVERSED); }
    /**
     * 历史只提供领域裁决所需的最小事实，不将所有账户快照载入列表。
     * @author owlzhangfq@gmail.com
     */
    public record DisputeEvidence(PaymentObservation firstSuccess, boolean fundingObserved) { }
    /** 升级后补建缺失的付款准备；只扫描实际成功记录，不查询或重发资金交易。 */
    public List<Candidate> missingVoucherPreparations() {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,s.process_instance_id FROM payment_operation q
                JOIN payment_authorization auth ON auth.tenant_id=q.tenant_id AND auth.id=q.id
                LEFT JOIN approval_application a ON a.tenant_id=auth.tenant_id AND a.id=auth.application_id
                LEFT JOIN approval_submission_round s ON s.tenant_id=a.tenant_id AND s.application_id=a.id AND s.round_no=auth.round_no
                WHERE q.status='SUCCEEDED'
                AND NOT EXISTS (SELECT 1 FROM voucher_preparation v WHERE v.tenant_id=auth.tenant_id AND v.application_id=auth.application_id AND v.round_no=auth.round_no AND v.kind='PAYMENT')
                AND NOT EXISTS (SELECT 1 FROM voucher_operation v WHERE v.tenant_id=auth.tenant_id AND v.application_id=auth.application_id AND v.round_no=auth.round_no AND v.kind='PAYMENT')
                ORDER BY q.updated_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")));
    }
    /** 扫描只取十个标识，不加载或输出账户及金额。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,s.process_instance_id FROM payment_operation q
                LEFT JOIN payment_authorization auth ON auth.tenant_id=q.tenant_id AND auth.id=q.id
                LEFT JOIN approval_application a ON a.tenant_id=auth.tenant_id AND a.id=auth.application_id
                LEFT JOIN approval_submission_round s ON s.tenant_id=a.tenant_id AND s.application_id=a.id AND s.round_no=auth.round_no
                WHERE (q.status IN ('QUEUED','UNKNOWN') AND q.next_attempt_at<=?) OR (q.status IN ('CHECKING','SENDING','QUERYING') AND q.lease_until<=?)
                ORDER BY COALESCE(q.next_attempt_at,q.lease_until),q.created_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")), timestamp(now), timestamp(now));
    }
    /** 旧版本的成功借款付款按原业务身份补建余额，不重新发送资金命令。 */
    public List<Candidate> missingAdvanceBalances() {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no,s.process_instance_id FROM payment_operation q
                JOIN payment_authorization auth ON auth.tenant_id=q.tenant_id AND auth.id=q.id
                LEFT JOIN approval_application a ON a.tenant_id=auth.tenant_id AND a.id=auth.application_id
                LEFT JOIN approval_submission_round s ON s.tenant_id=a.tenant_id AND s.application_id=a.id AND s.round_no=auth.round_no
                WHERE q.status='SUCCEEDED' AND auth.purpose='EMPLOYEE_ADVANCE'
                AND NOT EXISTS (SELECT 1 FROM finance_resource r WHERE r.tenant_id=auth.tenant_id AND r.resource_type='ADVANCE' AND r.id=auth.business_id)
                ORDER BY q.updated_at,q.id LIMIT 10
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no"), row.getString("process_instance_id")));
    }
    private RowMapper<PaymentOperation> row() {
        return (row, index) -> {
            var value = json.read(row.getString("state_json"), PaymentOperation.class); var command = value.input().command();
            if (!value.input().equals(json.read(row.getString("input_json"), PaymentOperation.Input.class)) || !command.digest().equals(row.getString("command_digest"))
                    || !command.tenantId().equals(row.getString("tenant_id")) || !command.id().toString().equals(row.getString("id"))
                    || value.version() != row.getLong("version") || !value.status().name().equals(row.getString("status")) || value.attempts() != row.getInt("attempts")
                    || value.dispatches() != row.getInt("dispatches") || value.highestRevision() != row.getLong("highest_revision")
                    || !value.createdAt().equals(instant(row.getTimestamp("created_at"))) || !value.updatedAt().equals(instant(row.getTimestamp("updated_at")))
                    || !Objects.equals(value.nextAttemptAt(), instant(row.getTimestamp("next_attempt_at"))) || !Objects.equals(value.leaseUntil(), instant(row.getTimestamp("lease_until")))) throw new IllegalStateException("Persisted payment operation identity is inconsistent");
            return value;
        };
    }
    private void append(PaymentOperation value) { jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,?,?)", value.input().command().tenantId(), value.input().command().id().toString(), value.version(), json.write(value)); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment operation input or version changed"); }

    /**
     * 调度候选不包含任何付款明细。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId, String businessNo, String processInstanceId) {
        /** 没有原业务事实的历史调用保留空值，不借用当前线程。 */
        public Candidate(String tenantId, UUID id, String traceId) { this(tenantId, id, traceId, null, null); }
    }
}
