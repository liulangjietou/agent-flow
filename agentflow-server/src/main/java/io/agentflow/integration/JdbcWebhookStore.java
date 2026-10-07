package io.agentflow.integration;

import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 投递与尝试记录共用本地事务，版本条件让多个 worker 只有一个能领取同一租约。
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcWebhookStore {
    private static final RowMapper<Delivery> MAPPER = (row, index) -> new Delivery(
            UUID.fromString(row.getString("id")), row.getString("tenant_id"), row.getString("target_id"), row.getString("destination_digest"),
            row.getString("event_id"), row.getString("event_type"), UUID.fromString(row.getString("application_id")), row.getLong("aggregate_version"),
            row.getString("payload_json"), row.getTimestamp("occurred_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
            new DeliveryProgress(DeliveryProgress.Status.valueOf(row.getString("status")), row.getLong("version"), row.getInt("attempts"),
                    row.getInt("cycle_attempts"), instant(row.getTimestamp("next_attempt_at")), instant(row.getTimestamp("lease_until")),
                    row.getString("lease_token"), row.getObject("http_status", Integer.class), row.getString("error_code")));
    private final JdbcTemplate jdbc;

    /** 注入与审批、审计共用的数据源。 */
    public JdbcWebhookStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 由审批事务调用，失败必须回滚业务；不在此方法中发起网络请求。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(String tenant, WebhookTargets.Destination target, String eventId, String eventType,
                       UUID applicationId, long version, String body, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO webhook_delivery (id,tenant_id,target_id,destination_digest,event_id,event_type,application_id,
                    aggregate_version,payload_json,occurred_at,status,version,attempts,cycle_attempts,next_attempt_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',1,0,0,?,?)
                """, UUID.randomUUID().toString(), tenant, target.id(), target.digest(), eventId, eventType, applicationId.toString(),
                version, body, timestamp(occurredAt), timestamp(occurredAt), timestamp(occurredAt));
    }

    /** 只提取有界候选标识，领取时再次复核版本和到期时间。 */
    public List<UUID> due(Instant now) {
        return jdbc.query("""
                SELECT id FROM webhook_delivery
                WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?) OR (status='IN_FLIGHT' AND lease_until<=?)
                ORDER BY updated_at,id LIMIT 10
                """, (row, index) -> UUID.fromString(row.getString("id")), timestamp(now), timestamp(now));
    }

    /** 按投递索引读取本租户业务号；原信封轮次缺失时不借用申请的当前轮次。 */
    public Optional<BusinessContext> businessContext(Delivery delivery, Integer originalRound) {
        return jdbc.query("""
                SELECT a.business_no,r.process_instance_id FROM approval_application a
                LEFT JOIN approval_submission_round r ON r.tenant_id=a.tenant_id AND r.application_id=a.id AND r.round_no=?
                WHERE a.tenant_id=? AND a.id=?
                """, (row, index) -> new BusinessContext(row.getString("business_no"), row.getString("process_instance_id")),
                originalRound, delivery.tenantId(), delivery.applicationId().toString()).stream().findFirst();
    }

    /** 独立短事务领取；过期尝试标记为结果未知，禁止把连接断开误记为失败未送达。 */
    @Transactional
    public Delivery claim(UUID id, Instant now) {
        Delivery current = internal(id);
        if (current == null || !current.progress().due(now)) return null;
        var next = current.progress().claim(now, UUID.randomUUID().toString());
        if (!update(current, next, now)) return null;
        if (current.progress().status() == DeliveryProgress.Status.IN_FLIGHT) {
            jdbc.update("UPDATE webhook_attempt SET finished_at=?,result='OUTCOME_UNKNOWN',error_code='OUTCOME_UNKNOWN' WHERE delivery_id=? AND attempt_no=? AND finished_at IS NULL",
                    timestamp(now), id.toString(), current.progress().attempts());
        }
        if (next.status() == DeliveryProgress.Status.IN_FLIGHT) jdbc.update("""
                INSERT INTO webhook_attempt (delivery_id,attempt_no,lease_token,started_at,result) VALUES (?,?,?,?,'IN_FLIGHT')
                """, id.toString(), next.attempts(), next.leaseToken(), timestamp(now));
        return current.progress(next, now);
    }

    /** 仅当前租约可确认结果，迟到响应不能覆盖其他 worker 的新尝试。 */
    @Transactional
    public boolean finish(Delivery claimed, DeliveryProgress.Outcome outcome, Instant now) {
        var next = claimed.progress().finish(claimed.progress().leaseToken(), outcome, now);
        if (!update(claimed, next, now)) return false;
        jdbc.update("""
                UPDATE webhook_attempt SET finished_at=?,result=?,http_status=?,error_code=?
                WHERE delivery_id=? AND attempt_no=? AND lease_token=? AND finished_at IS NULL
                """, timestamp(now), outcome.success() ? "DELIVERED" : "FAILED", outcome.httpStatus(), outcome.errorCode(),
                claimed.id().toString(), claimed.progress().attempts(), claimed.progress().leaseToken());
        return true;
    }

    /** 当前租户资源查询；跨租户或不存在统一返回 404。 */
    public Delivery get(String tenant, UUID id) {
        return jdbc.query("SELECT * FROM webhook_delivery WHERE tenant_id=? AND id=?", MAPPER, tenant, id.toString()).stream()
                .findFirst().orElseThrow(() -> new DomainException("NOT_FOUND", "Webhook delivery not found"));
    }

    /** 人工重试与请求审计同事务；事件 ID、目标摘要和正文永不重建。 */
    @Transactional
    public Delivery retry(Delivery current, long expectedVersion, String actor, Instant now) {
        var next = current.progress().retry(expectedVersion, now);
        if (!update(current, next, now)) throw new DomainException("WEBHOOK_DELIVERY_CONFLICT", "Webhook delivery state has changed");
        jdbc.update("INSERT INTO webhook_retry_request (id,delivery_id,requested_by,requested_at,previous_status,previous_version) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), current.id().toString(), actor, timestamp(now), current.progress().status().name(), current.progress().version());
        return current.progress(next, now);
    }

    /** 管理页只查摘要；不加载请求体与任何签名密钥。 */
    public List<Summary> search(String tenant, WebhookQueryParameters query) {
        var parameters = new ArrayList<Object>(List.of(tenant));
        StringBuilder sql = new StringBuilder("SELECT id,target_id,event_id,event_type,application_id,aggregate_version,occurred_at,updated_at,status,version,attempts,cycle_attempts,next_attempt_at,lease_until,http_status,error_code FROM webhook_delivery WHERE tenant_id=?");
        if (!query.target().isEmpty()) { sql.append(" AND target_id=?"); parameters.add(query.target()); }
        if (!query.status().isEmpty()) { sql.append(" AND status=?"); parameters.add(query.status()); }
        if (query.applicationId() != null) { sql.append(" AND application_id=?"); parameters.add(query.applicationId().toString()); }
        if (query.beforeTime() != null) {
            sql.append(" AND (occurred_at<? OR (occurred_at=? AND id<?))");
            parameters.add(timestamp(query.beforeTime())); parameters.add(timestamp(query.beforeTime())); parameters.add(query.beforeId().toString());
        }
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ?"); parameters.add(query.limit() + 1);
        return jdbc.query(sql.toString(), (row, index) -> new Summary(UUID.fromString(row.getString("id")), row.getString("target_id"),
                row.getString("event_id"), row.getString("event_type"), UUID.fromString(row.getString("application_id")), row.getLong("aggregate_version"),
                row.getTimestamp("occurred_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getString("status"), row.getLong("version"),
                row.getInt("attempts"), row.getInt("cycle_attempts"), instant(row.getTimestamp("next_attempt_at")), instant(row.getTimestamp("lease_until")),
                row.getObject("http_status", Integer.class), row.getString("error_code")), parameters.toArray());
    }

    /** 单次聚合读取完整范围；每个事件与目的地的投递只计一次，不联查重试明细或当前配置。 */
    public Overview overview(String tenant, WebhookQueryParameters query) {
        Instant queriedAt = Instant.now();
        var parameters = new ArrayList<Object>(List.of(tenant));
        var sql = new StringBuilder("""
                SELECT COUNT(*) AS total,
                    COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending,
                    COALESCE(SUM(CASE WHEN status='IN_FLIGHT' THEN 1 ELSE 0 END),0) AS in_flight,
                    COALESCE(SUM(CASE WHEN status='RETRY_WAIT' THEN 1 ELSE 0 END),0) AS retry_wait,
                    COALESCE(SUM(CASE WHEN status='DELIVERED' THEN 1 ELSE 0 END),0) AS delivered,
                    COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failed
                FROM webhook_delivery WHERE tenant_id=?
                """);
        if (!query.target().isEmpty()) { sql.append(" AND target_id=?"); parameters.add(query.target()); }
        if (query.applicationId() != null) { sql.append(" AND application_id=?"); parameters.add(query.applicationId().toString()); }
        return jdbc.queryForObject(sql.toString(), (row, index) -> new Overview(queriedAt, row.getLong("total"), row.getLong("pending"),
                row.getLong("in_flight"), row.getLong("retry_wait"), row.getLong("delivered"), row.getLong("failed")), parameters.toArray());
    }

    /** 返回最近 50 次真实尝试；总次数在摘要中单独披露。 */
    public List<Attempt> attempts(UUID id) {
        return jdbc.query("SELECT * FROM webhook_attempt WHERE delivery_id=? ORDER BY attempt_no DESC LIMIT 50", (row, index) -> new Attempt(
                row.getInt("attempt_no"), row.getTimestamp("started_at").toInstant(), instant(row.getTimestamp("finished_at")), row.getString("result"),
                row.getObject("http_status", Integer.class), row.getString("error_code")), id.toString());
    }

    /** 返回最近 20 次人工重试记录，不覆盖原来的操作人和处理时间。 */
    public List<RetryRequest> retries(UUID id) {
        return jdbc.query("SELECT * FROM webhook_retry_request WHERE delivery_id=? ORDER BY requested_at DESC,id DESC LIMIT 20", (row, index) -> new RetryRequest(
                row.getString("requested_by"), row.getTimestamp("requested_at").toInstant(), row.getString("previous_status"), row.getLong("previous_version")), id.toString());
    }

    private Delivery internal(UUID id) { return jdbc.query("SELECT * FROM webhook_delivery WHERE id=?", MAPPER, id.toString()).stream().findFirst().orElse(null); }
    private boolean update(Delivery current, DeliveryProgress next, Instant now) {
        return jdbc.update("""
                UPDATE webhook_delivery SET status=?,version=?,attempts=?,cycle_attempts=?,next_attempt_at=?,lease_until=?,lease_token=?,http_status=?,error_code=?,updated_at=?
                WHERE tenant_id=? AND id=? AND version=?
                """, next.status().name(), next.version(), next.attempts(), next.cycleAttempts(), timestamp(next.nextAttemptAt()), timestamp(next.leaseUntil()),
                next.leaseToken(), next.httpStatus(), next.errorCode(), timestamp(now), current.tenantId(), current.id().toString(), current.progress().version()) == 1;
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    /**
     * 基础设施持久化记录，包含不可变事件体，不作为 HTTP 响应。
     * @author owlzhangfq@gmail.com
     */
    public record Delivery(UUID id, String tenantId, String targetId, String destinationDigest, String eventId, String eventType,
                           UUID applicationId, long aggregateVersion, String body, Instant occurredAt, Instant updatedAt, DeliveryProgress progress) {
        /** 将领域投递状态与原始事件身份组合，不修改事件事实。 */
        public Delivery progress(DeliveryProgress next, Instant now) { return new Delivery(id, tenantId, targetId, destinationDigest, eventId, eventType, applicationId, aggregateVersion, body, occurredAt, now, next); }
        /** 对外摘要不含正文或连接配置。 */
        public Summary summary() { return new Summary(id, targetId, eventId, eventType, applicationId, aggregateVersion, occurredAt, updatedAt,
                progress.status().name(), progress.version(), progress.attempts(), progress.cycleAttempts(), progress.nextAttemptAt(), progress.leaseUntil(), progress.httpStatus(), progress.errorCode()); }
    }
    /**
     * 租户管理摘要。
     * @author owlzhangfq@gmail.com
     */
    public record Summary(UUID id, String targetId, String eventId, String eventType, UUID applicationId, long aggregateVersion,
                          Instant occurredAt, Instant updatedAt, String status, long version, int attempts, int cycleAttempts,
                          Instant nextAttemptAt, Instant leaseUntil, Integer httpStatus, String errorCode) { }
    /**
     * 查询开始时刻及完整状态数量，记录当前投递状态，不表达外部业务成功率。
     * @author owlzhangfq@gmail.com
     */
    public record Overview(Instant queriedAt, long total, long pending, long inFlight, long retryWait, long delivered, long failed) { }
    /**
     * 一次网络尝试的真实结果；租约失联时可能为 OUTCOME_UNKNOWN。
     * @author owlzhangfq@gmail.com
     */
    public record Attempt(int attemptNo, Instant startedAt, Instant finishedAt, String result, Integer httpStatus, String errorCode) { }
    /**
     * 人工重试的追加请求记录。
     * @author owlzhangfq@gmail.com
     */
    public record RetryRequest(String requestedBy, Instant requestedAt, String previousStatus, long previousVersion) { }

    /**
     * 已匹配本租户申请的诊断投影，不对外暴露或写回签名正文。
     * @author owlzhangfq@gmail.com
     */
    public record BusinessContext(String businessNo, String processInstanceId) { }
}
