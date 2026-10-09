package io.agentflow.integration.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcWebhookStore 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface WebhookStoreMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("targetId") String targetId,
            @Param("destinationDigest") String destinationDigest,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("applicationId") String applicationId,
            @Param("aggregateVersion") Long aggregateVersion,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 businessContext 所需的持久化事实。 */
    List<SqlRow> businessContext(
            @Param("originalRound") Integer originalRound,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId);

    /** 更新 claim 所需的持久化事实。 */
    int claim(
            @Param("finishedAt") Timestamp finishedAt,
            @Param("id") String id,
            @Param("attempts") Object attempts);

    /** 新增 claim 所需的持久化事实。 */
    int claim2(
            @Param("deliveryId") String deliveryId,
            @Param("attemptNo") Object attemptNo,
            @Param("leaseToken") Object leaseToken,
            @Param("startedAt") Timestamp startedAt);

    /** 更新 finish 所需的持久化事实。 */
    int finish(
            @Param("finishedAt") Timestamp finishedAt,
            @Param("success") String success,
            @Param("httpStatus") Integer httpStatus,
            @Param("errorCode") String errorCode,
            @Param("id") String id,
            @Param("attempts") Object attempts,
            @Param("leaseToken") Object leaseToken);

    /** 读取 get 所需的持久化事实。 */
    List<SqlRow> get(@Param("tenant") String tenant, @Param("id") String id);

    /** 新增 retry 所需的持久化事实。 */
    int retry(
            @Param("id") String id,
            @Param("deliveryId") String deliveryId,
            @Param("requestedBy") String requestedBy,
            @Param("requestedAt") Timestamp requestedAt,
            @Param("previousStatus") String previousStatus,
            @Param("previousVersion") Object previousVersion);

    /** 读取 attempts 所需的持久化事实。 */
    List<SqlRow> attempts(@Param("id") String id);

    /** 读取 retries 所需的持久化事实。 */
    List<SqlRow> retries(@Param("id") String id);

    /** 读取 internal 所需的持久化事实。 */
    List<SqlRow> internal(@Param("id") String id);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("attempts") Integer attempts,
            @Param("cycleAttempts") Integer cycleAttempts,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("leaseToken") String leaseToken,
            @Param("httpStatus") Integer httpStatus,
            @Param("errorCode") String errorCode,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Object expectedVersion);

    /** 按 search 的筛选条件执行数据库查询。 */
    List<SqlRow> searchQuery(
            @Param("hasTarget") boolean hasTarget,
            @Param("hasStatus") boolean hasStatus,
            @Param("hasApplicationId") boolean hasApplicationId,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);

    /** 按 overview 的筛选条件执行数据库查询。 */
    List<SqlRow> overviewQuery(
            @Param("hasTarget") boolean hasTarget,
            @Param("hasApplicationId") boolean hasApplicationId,
            @Param("parameters") Object[] parameters);
}
