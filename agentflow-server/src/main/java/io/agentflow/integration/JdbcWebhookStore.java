package io.agentflow.integration;

import io.agentflow.common.DomainException;
import io.agentflow.integration.mapper.WebhookStoreMapper;
import io.agentflow.mybatis.SqlRow;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 投递与尝试记录共用本地事务，版本条件让多个 worker 只有一个能领取同一租约。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcWebhookStore {
    private static final Function<SqlRow, Delivery> MAPPER =
            row ->
                    new Delivery(
                            UUID.fromString(row.getString("id")),
                            row.getString("tenant_id"),
                            row.getString("target_id"),
                            row.getString("destination_digest"),
                            row.getString("event_id"),
                            row.getString("event_type"),
                            UUID.fromString(row.getString("application_id")),
                            row.getLong("aggregate_version"),
                            row.getString("payload_json"),
                            row.getTimestamp("occurred_at").toInstant(),
                            row.getTimestamp("updated_at").toInstant(),
                            new DeliveryProgress(
                                    DeliveryProgress.Status.valueOf(row.getString("status")),
                                    row.getLong("version"),
                                    row.getInt("attempts"),
                                    row.getInt("cycle_attempts"),
                                    instant(row.getTimestamp("next_attempt_at")),
                                    instant(row.getTimestamp("lease_until")),
                                    row.getString("lease_token"),
                                    row.getObject("http_status", Integer.class),
                                    row.getString("error_code")));
    private final WebhookStoreMapper sqlMapper;

    /** 注入与审批、审计共用的数据源。 */
    public JdbcWebhookStore(WebhookStoreMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 由审批事务调用，失败必须回滚业务；不在此方法中发起网络请求。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(
            String tenant,
            WebhookTargets.Destination target,
            String eventId,
            String eventType,
            UUID applicationId,
            long version,
            String body,
            Instant occurredAt) {
        sqlMapper.append(
                UUID.randomUUID().toString(),
                tenant,
                target.id(),
                target.digest(),
                eventId,
                eventType,
                applicationId.toString(),
                version,
                body,
                timestamp(occurredAt),
                timestamp(occurredAt),
                timestamp(occurredAt));
    }

    /** 只提取有界候选标识，领取时再次复核版本和到期时间。 */
    public List<UUID> due(Instant now) {
        return SqlRows.map(
                sqlMapper.due(timestamp(now), timestamp(now)),
                row -> UUID.fromString(row.getString("id")));
    }

    /** 按投递索引读取本租户业务号；原信封轮次缺失时不借用申请的当前轮次。 */
    public Optional<BusinessContext> businessContext(Delivery delivery, Integer originalRound) {
        return SqlRows.map(
                        sqlMapper.businessContext(
                                originalRound,
                                delivery.tenantId(),
                                delivery.applicationId().toString()),
                        row ->
                                new BusinessContext(
                                        row.getString("business_no"),
                                        row.getString("process_instance_id")))
                .stream()
                .findFirst();
    }

    /** 独立短事务领取；过期尝试标记为结果未知，禁止把连接断开误记为失败未送达。 */
    @Transactional
    public Delivery claim(UUID id, Instant now) {
        Delivery current = internal(id);
        if (current == null || !current.progress().due(now)) return null;
        var next = current.progress().claim(now, UUID.randomUUID().toString());
        if (!update(current, next, now)) return null;
        if (current.progress().status() == DeliveryProgress.Status.IN_FLIGHT) {
            sqlMapper.claim(timestamp(now), id.toString(), current.progress().attempts());
        }
        if (next.status() == DeliveryProgress.Status.IN_FLIGHT)
            sqlMapper.claim2(id.toString(), next.attempts(), next.leaseToken(), timestamp(now));
        return current.progress(next, now);
    }

    /** 仅当前租约可确认结果，迟到响应不能覆盖其他 worker 的新尝试。 */
    @Transactional
    public boolean finish(Delivery claimed, DeliveryProgress.Outcome outcome, Instant now) {
        var next = claimed.progress().finish(claimed.progress().leaseToken(), outcome, now);
        if (!update(claimed, next, now)) return false;
        sqlMapper.finish(
                timestamp(now),
                outcome.success() ? "DELIVERED" : "FAILED",
                outcome.httpStatus(),
                outcome.errorCode(),
                claimed.id().toString(),
                claimed.progress().attempts(),
                claimed.progress().leaseToken());
        return true;
    }

    /** 当前租户资源查询；跨租户或不存在统一返回 404。 */
    public Delivery get(String tenant, UUID id) {
        return SqlRows.map(sqlMapper.get(tenant, id.toString()), MAPPER).stream()
                .findFirst()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Webhook delivery not found"));
    }

    /** 人工重试与请求审计同事务；事件 ID、目标摘要和正文永不重建。 */
    @Transactional
    public Delivery retry(Delivery current, long expectedVersion, String actor, Instant now) {
        var next = current.progress().retry(expectedVersion, now);
        if (!update(current, next, now))
            throw new DomainException(
                    "WEBHOOK_DELIVERY_CONFLICT", "Webhook delivery state has changed");
        sqlMapper.retry(
                UUID.randomUUID().toString(),
                current.id().toString(),
                actor,
                timestamp(now),
                current.progress().status().name(),
                current.progress().version());
        return current.progress(next, now);
    }

    /** 管理页只查摘要；不加载请求体与任何签名密钥。 */
    public List<Summary> search(String tenant, WebhookQueryParameters query) {
        var parameters = new ArrayList<Object>(List.of(tenant));

        if (!query.target().isEmpty()) {
            parameters.add(query.target());
        }
        if (!query.status().isEmpty()) {
            parameters.add(query.status());
        }
        if (query.applicationId() != null) {
            parameters.add(query.applicationId().toString());
        }
        if (query.beforeTime() != null) {

            parameters.add(timestamp(query.beforeTime()));
            parameters.add(timestamp(query.beforeTime()));
            parameters.add(query.beforeId().toString());
        }
        parameters.add(query.limit() + 1);
        return SqlRows.map(
                sqlMapper.searchQuery(
                        (!query.target().isEmpty()),
                        (!query.status().isEmpty()),
                        (query.applicationId() != null),
                        (query.beforeTime() != null),
                        parameters.toArray()),
                row ->
                        new Summary(
                                UUID.fromString(row.getString("id")),
                                row.getString("target_id"),
                                row.getString("event_id"),
                                row.getString("event_type"),
                                UUID.fromString(row.getString("application_id")),
                                row.getLong("aggregate_version"),
                                row.getTimestamp("occurred_at").toInstant(),
                                row.getTimestamp("updated_at").toInstant(),
                                row.getString("status"),
                                row.getLong("version"),
                                row.getInt("attempts"),
                                row.getInt("cycle_attempts"),
                                instant(row.getTimestamp("next_attempt_at")),
                                instant(row.getTimestamp("lease_until")),
                                row.getObject("http_status", Integer.class),
                                row.getString("error_code")));
    }

    /** 单次聚合读取完整范围；每个事件与目的地的投递只计一次，不联查重试明细或当前配置。 */
    public Overview overview(String tenant, WebhookQueryParameters query) {
        Instant queriedAt = Instant.now();
        var parameters = new ArrayList<Object>(List.of(tenant));

        if (!query.target().isEmpty()) {
            parameters.add(query.target());
        }
        if (query.applicationId() != null) {
            parameters.add(query.applicationId().toString());
        }
        return SqlRows.single(
                SqlRows.map(
                        sqlMapper.overviewQuery(
                                (!query.target().isEmpty()),
                                (query.applicationId() != null),
                                parameters.toArray()),
                        row ->
                                new Overview(
                                        queriedAt,
                                        row.getLong("total"),
                                        row.getLong("pending"),
                                        row.getLong("in_flight"),
                                        row.getLong("retry_wait"),
                                        row.getLong("delivered"),
                                        row.getLong("failed"))));
    }

    /** 返回最近 50 次真实尝试；总次数在摘要中单独披露。 */
    public List<Attempt> attempts(UUID id) {
        return SqlRows.map(
                sqlMapper.attempts(id.toString()),
                row ->
                        new Attempt(
                                row.getInt("attempt_no"),
                                row.getTimestamp("started_at").toInstant(),
                                instant(row.getTimestamp("finished_at")),
                                row.getString("result"),
                                row.getObject("http_status", Integer.class),
                                row.getString("error_code")));
    }

    /** 返回最近 20 次人工重试记录，不覆盖原来的操作人和处理时间。 */
    public List<RetryRequest> retries(UUID id) {
        return SqlRows.map(
                sqlMapper.retries(id.toString()),
                row ->
                        new RetryRequest(
                                row.getString("requested_by"),
                                row.getTimestamp("requested_at").toInstant(),
                                row.getString("previous_status"),
                                row.getLong("previous_version")));
    }

    private Delivery internal(UUID id) {
        return SqlRows.map(sqlMapper.internal(id.toString()), MAPPER).stream()
                .findFirst()
                .orElse(null);
    }

    private boolean update(Delivery current, DeliveryProgress next, Instant now) {
        return sqlMapper.update(
                        next.status().name(),
                        next.version(),
                        next.attempts(),
                        next.cycleAttempts(),
                        timestamp(next.nextAttemptAt()),
                        timestamp(next.leaseUntil()),
                        next.leaseToken(),
                        next.httpStatus(),
                        next.errorCode(),
                        timestamp(now),
                        current.tenantId(),
                        current.id().toString(),
                        current.progress().version())
                == 1;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    /**
     * 基础设施持久化记录，包含不可变事件体，不作为 HTTP 响应。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Delivery(
            UUID id,
            String tenantId,
            String targetId,
            String destinationDigest,
            String eventId,
            String eventType,
            UUID applicationId,
            long aggregateVersion,
            String body,
            Instant occurredAt,
            Instant updatedAt,
            DeliveryProgress progress) {
        /** 将领域投递状态与原始事件身份组合，不修改事件事实。 */
        public Delivery progress(DeliveryProgress next, Instant now) { return new Delivery(id, tenantId, targetId, destinationDigest, eventId, eventType, applicationId, aggregateVersion, body, occurredAt, now, next); }

        /** 对外摘要不含正文或连接配置。 */
        public Summary summary() { return new Summary(id, targetId, eventId, eventType, applicationId, aggregateVersion, occurredAt, updatedAt,
                progress.status().name(), progress.version(), progress.attempts(), progress.cycleAttempts(), progress.nextAttemptAt(), progress.leaseUntil(), progress.httpStatus(), progress.errorCode()); }
    }

    /**
     * 租户管理摘要。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Summary(
            UUID id,
            String targetId,
            String eventId,
            String eventType,
            UUID applicationId,
            long aggregateVersion,
            Instant occurredAt,
            Instant updatedAt,
            String status,
            long version,
            int attempts,
            int cycleAttempts,
            Instant nextAttemptAt,
            Instant leaseUntil,
            Integer httpStatus,
            String errorCode) {}

    /**
     * 查询开始时刻及完整状态数量，记录当前投递状态，不表达外部业务成功率。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Overview(
            Instant queriedAt,
            long total,
            long pending,
            long inFlight,
            long retryWait,
            long delivered,
            long failed) {}

    /**
     * 一次网络尝试的真实结果；租约失联时可能为 OUTCOME_UNKNOWN。
     *
     * @author owlzhangfq@gmail.com
     */
    public record Attempt(
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            String result,
            Integer httpStatus,
            String errorCode) {}

    /**
     * 人工重试的追加请求记录。
     *
     * @author owlzhangfq@gmail.com
     */
    public record RetryRequest(
            String requestedBy, Instant requestedAt, String previousStatus, long previousVersion) {}

    /**
     * 已匹配本租户申请的诊断投影，不对外暴露或写回签名正文。
     *
     * @author owlzhangfq@gmail.com
     */
    public record BusinessContext(String businessNo, String processInstanceId) {}
}
