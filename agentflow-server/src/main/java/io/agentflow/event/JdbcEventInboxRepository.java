package io.agentflow.event;

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
 * 收件事实、处理修订和历史与引擎共享数据库事务，输入 JSON 永不随处理更新。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcEventInboxRepository implements EventInboxRepository {
    private static final int BATCH_SIZE = 20;
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    /** JSON 只保存已认证的明确元数据，不接收未经验证的原始正文。 */
    public JdbcEventInboxRepository(JdbcTemplate jdbc, JsonUtil json) { this.jdbc = jdbc; this.json = json; }
    @Override public Optional<EventInboxItem> byEvent(String tenantId, String sourceKey, String eventId) {
        return jdbc.query("SELECT * FROM event_inbox WHERE tenant_id=? AND source_key=? AND event_id=?", row(), tenantId, sourceKey, eventId).stream().findFirst();
    }
    @Override public EventInboxItem get(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM event_inbox WHERE tenant_id=? AND id=?", row(), tenantId, id.toString()).stream().findFirst().orElseThrow(JdbcEventInboxRepository::missing);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public EventInboxItem lock(String tenantId, UUID id) {
        return jdbc.query("SELECT * FROM event_inbox WHERE tenant_id=? AND id=? FOR UPDATE", row(), tenantId, id.toString()).stream().findFirst().orElseThrow(JdbcEventInboxRepository::missing);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void create(EventInboxItem item) {
        if (item.version() != 1 || item.status() != EventInboxItem.Status.RECEIVED) throw conflict();
        var signal = item.input().signal();
        jdbc.update("""
                INSERT INTO event_inbox(tenant_id,id,source_key,event_id,application_id,contract_key,contract_version,input_json,
                    version,status,received_at,updated_at,next_attempt_at,failures,trace_id)
                VALUES(?,?,?,?,?,?,?,?,1,'RECEIVED',?,?,?,0,?)
                """, signal.tenantId(), item.id().toString(), signal.sourceKey(), item.input().eventId(), signal.applicationId().toString(),
                signal.contractKey(), signal.contractVersion(), json.write(item.input()), timestamp(item.receivedAt()), timestamp(item.updatedAt()), timestamp(item.nextAttemptAt()), DiagnosticContext.capture().traceId());
        append(item);
    }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void update(EventInboxItem item) {
        var signal = item.input().signal();
        int changed = jdbc.update("""
                UPDATE event_inbox SET version=?,status=?,updated_at=?,next_attempt_at=?,failures=?,reason=?,error_code=?,requested_by=?,request_reason=?
                WHERE tenant_id=? AND id=? AND version=? AND input_json=? AND received_at=?
                """, item.version(), item.status().name(), timestamp(item.updatedAt()), timestamp(item.nextAttemptAt()), item.failures(),
                item.reason() == null ? null : item.reason().name(), item.errorCode(), item.requestedBy(), item.requestReason(),
                signal.tenantId(), item.id().toString(), item.version() - 1, json.write(item.input()), timestamp(item.receivedAt()));
        if (changed != 1) throw conflict(); append(item);
    }
    @Override public List<Candidate> due(Instant now) {
        return jdbc.query("""
                SELECT q.tenant_id,q.id,q.trace_id,a.business_no FROM event_inbox q
                LEFT JOIN approval_application a ON a.tenant_id=q.tenant_id AND a.id=q.application_id
                WHERE q.next_attempt_at<=? ORDER BY q.next_attempt_at,q.received_at,q.id LIMIT ?
                """, (row, index) -> new Candidate(row.getString("tenant_id"), UUID.fromString(row.getString("id")),
                row.getString("trace_id"), row.getString("business_no")), timestamp(now), BATCH_SIZE);
    }
    /** 原收件已由 get 核对索引和信封后才读取原轮次，不在全队列扫描中解析信封。 */
    @Override public Optional<String> findProcessInstance(EventInboxItem item) {
        var signal = item.input().signal();
        return jdbc.query("SELECT process_instance_id FROM approval_submission_round WHERE tenant_id=? AND application_id=? AND round_no=?",
                (row, index) -> row.getString("process_instance_id"), signal.tenantId(), signal.applicationId().toString(), signal.roundNo()).stream().findFirst();
    }
    @Override public List<EventInboxItem> page(String tenantId, int limit, UUID beforeId) {
        if (beforeId == null) return jdbc.query("SELECT * FROM event_inbox WHERE tenant_id=? ORDER BY received_at DESC,id DESC LIMIT ?", row(), tenantId, limit + 1);
        var before = get(tenantId, beforeId);
        return jdbc.query("SELECT * FROM event_inbox WHERE tenant_id=? AND (received_at<? OR (received_at=? AND id<?)) ORDER BY received_at DESC,id DESC LIMIT ?",
                row(), tenantId, timestamp(before.receivedAt()), timestamp(before.receivedAt()), beforeId.toString(), limit + 1);
    }
    @Override public List<EventInboxItem> history(String tenantId, UUID id, int limit, Long beforeVersion) {
        String filter = beforeVersion == null ? "" : " AND version<?";
        Object[] args = beforeVersion == null ? new Object[]{tenantId, id.toString(), limit + 1} : new Object[]{tenantId, id.toString(), beforeVersion, limit + 1};
        return jdbc.query("SELECT state_json FROM event_inbox_revision WHERE tenant_id=? AND inbox_id=?" + filter + " ORDER BY version DESC LIMIT ?",
                (row, index) -> json.read(row.getString("state_json"), EventInboxItem.class), args);
    }
    private RowMapper<EventInboxItem> row() {
        return (row, index) -> {
            var input = json.read(row.getString("input_json"), ReceivedEvent.class); var signal = input.signal();
            // 索引列和不可变正文必须描述同一事实，防止旧代码或损坏行静默串到另一来源。
            if (!signal.tenantId().equals(row.getString("tenant_id")) || !signal.sourceKey().equals(row.getString("source_key"))
                    || !input.eventId().equals(row.getString("event_id")) || !signal.applicationId().toString().equals(row.getString("application_id"))
                    || !signal.contractKey().equals(row.getString("contract_key")) || signal.contractVersion() != row.getLong("contract_version")) {
                throw new IllegalStateException("Event inbox identity is inconsistent");
            }
            return new EventInboxItem(UUID.fromString(row.getString("id")), input, row.getLong("version"), EventInboxItem.Status.valueOf(row.getString("status")),
                    instant(row.getTimestamp("received_at")), instant(row.getTimestamp("updated_at")), instant(row.getTimestamp("next_attempt_at")), row.getInt("failures"),
                    row.getString("reason") == null ? null : EventInboxItem.Reason.valueOf(row.getString("reason")), row.getString("error_code"), row.getString("requested_by"), row.getString("request_reason"));
        };
    }
    private void append(EventInboxItem item) {
        jdbc.update("INSERT INTO event_inbox_revision(tenant_id,inbox_id,version,state_json,occurred_at) VALUES(?,?,?,?,?)",
                item.input().signal().tenantId(), item.id().toString(), item.version(), json.write(item), timestamp(item.updatedAt()));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static DomainException missing() { return new DomainException("NOT_FOUND", "Event inbox item was not found"); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Event inbox identity or version changed"); }
}
