package io.agentflow.finance.callback;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回调收件箱及追加历史与原查询排队同事务，事件号唯一约束覆盖多实例重复投递。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcPaymentCallbackRepository {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** 仓储只保存已经验签并核对原来源的回调事实。 */
    public JdbcPaymentCallbackRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }

    /** 按已认证租户查找事件，重新投递的时间戳不改变原事件身份。 */
    public Optional<PaymentCallback> byEvent(String tenant, String event) {
        return jdbc.query("SELECT * FROM payment_callback WHERE tenant_id=? AND event_id=?", row(), tenant, event).stream().findFirst();
    }
    /** 当前租户详情，不返回原始回调正文或签名。 */
    public PaymentCallback get(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM payment_callback WHERE tenant_id=? AND id=?", row(), tenant, id.toString()).stream().findFirst().orElseThrow(JdbcPaymentCallbackRepository::missing);
    }
    /** 调用方先锁原付款来源；此锁仅串行化同一回调的处理或人工重试。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentCallback lock(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM payment_callback WHERE tenant_id=? AND id=? FOR UPDATE", row(), tenant, id.toString()).stream().findFirst().orElseThrow(JdbcPaymentCallbackRepository::missing);
    }
    /** 入箱和首条历史同时保存，外部资金尚未调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(PaymentCallback value) {
        if (value.version() != 1 || value.status() != PaymentCallback.Status.RECEIVED) throw conflict();
        var input = value.input(); var signal = input.signal(); var id = signal.authorizationId().toString();
        jdbc.update("""
                INSERT INTO payment_callback(tenant_id,id,event_id,payload_digest,target_digest,payment_kind,authorization_id,command_digest,source_revision,
                    employee_payment_id,supplier_payment_id,version,status,received_at,updated_at,next_attempt_at,failures,trace_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,1,'RECEIVED',?,?,?,0,?)
                """, signal.tenantId(), value.id().toString(), input.eventId(), input.payloadDigest(), input.targetDigest(), signal.kind().name(), id,
                signal.commandDigest(), signal.sourceRevision(), signal.kind() == PaymentCallbackVerifier.Kind.EMPLOYEE ? id : null,
                signal.kind() == PaymentCallbackVerifier.Kind.SUPPLIER ? id : null, timestamp(value.receivedAt()), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), DiagnosticContext.capture().traceId());
        append(value);
    }
    /** 只推进处理状态；事件身份、原始摘要及付款来源不允许变更。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(PaymentCallback value) {
        var input = value.input(); var signal = input.signal();
        int changed = jdbc.update("""
                UPDATE payment_callback SET version=?,status=?,updated_at=?,next_attempt_at=?,failures=?,query_version=?,reason=?,requested_by=?,request_reason=?
                WHERE tenant_id=? AND id=? AND version=? AND event_id=? AND payload_digest=? AND target_digest=?
                    AND payment_kind=? AND authorization_id=? AND command_digest=? AND source_revision=? AND received_at=?
                """, value.version(), value.status().name(), timestamp(value.updatedAt()), timestamp(value.nextAttemptAt()), value.failures(), value.queryVersion(),
                value.reason() == null ? null : value.reason().name(), value.requestedBy(), value.requestReason(), signal.tenantId(), value.id().toString(), value.version() - 1,
                input.eventId(), input.payloadDigest(), input.targetDigest(), signal.kind().name(), signal.authorizationId().toString(), signal.commandDigest(),
                signal.sourceRevision(), timestamp(value.receivedAt()));
        if (changed != 1) throw conflict(); append(value);
    }
    /** 每批只取十个标识，单条失败不会无界占用线程或加载财务数据。 */
    public List<Candidate> due(Instant now) {
        return jdbc.query("SELECT tenant_id,id,trace_id FROM payment_callback WHERE next_attempt_at<=? ORDER BY next_attempt_at,received_at,id LIMIT 10",
                (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")), row.getString("trace_id")), timestamp(now));
    }
    /** 以本租户真实旧行作游标，有界查询不会混入其他租户的事件。 */
    public List<PaymentCallback> page(String tenant, int limit, UUID beforeId) {
        if (beforeId == null) return jdbc.query("SELECT * FROM payment_callback WHERE tenant_id=? ORDER BY received_at DESC,id DESC LIMIT ?", row(), tenant, limit + 1);
        var before = get(tenant, beforeId);
        return jdbc.query("SELECT * FROM payment_callback WHERE tenant_id=? AND (received_at<? OR (received_at=? AND id<?)) ORDER BY received_at DESC,id DESC LIMIT ?",
                row(), tenant, timestamp(before.receivedAt()), timestamp(before.receivedAt()), before.id().toString(), limit + 1);
    }
    /** 最新五十次状态记录，原始事件摘要另由当前记录永久保留。 */
    public List<PaymentCallback> history(String tenant, UUID id) {
        return jdbc.query("SELECT state_json FROM payment_callback_revision WHERE tenant_id=? AND callback_id=? ORDER BY version DESC LIMIT 50",
                (row, index) -> json.read(row.getString("state_json"), PaymentCallback.class), tenant, id.toString());
    }
    private RowMapper<PaymentCallback> row() {
        return (row, index) -> {
            var signal = new PaymentCallbackVerifier.Signal(1, "payment.changed", row.getString("tenant_id"), PaymentCallbackVerifier.Kind.valueOf(row.getString("payment_kind")),
                    UUID.fromString(row.getString("authorization_id")), row.getString("command_digest"), row.getLong("source_revision"));
            var input = new PaymentCallbackVerifier.Verified(row.getString("event_id"), row.getString("payload_digest"), row.getString("target_digest"), signal);
            return new PaymentCallback(UUID.fromString(row.getString("id")), input, row.getLong("version"), PaymentCallback.Status.valueOf(row.getString("status")),
                    instant(row.getTimestamp("received_at")), instant(row.getTimestamp("updated_at")), instant(row.getTimestamp("next_attempt_at")), row.getInt("failures"),
                    row.getObject("query_version") == null ? null : row.getLong("query_version"), row.getString("reason") == null ? null : PaymentCallback.Reason.valueOf(row.getString("reason")), row.getString("requested_by"), row.getString("request_reason"));
        };
    }
    private void append(PaymentCallback value) {
        jdbc.update("INSERT INTO payment_callback_revision(tenant_id,callback_id,version,state_json,occurred_at) VALUES(?,?,?,?,?)",
                value.input().signal().tenantId(), value.id().toString(), value.version(), json.write(value), timestamp(value.updatedAt()));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException missing() { return new DomainException("NOT_FOUND", "Payment callback was not found"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Payment callback identity or version changed"); }

    /**
     * 调度不加载回调正文和资金明细。
     * @author owlzhangfq@gmail.com
     */
    public record Candidate(String tenantId, UUID id, String traceId) { }
}
