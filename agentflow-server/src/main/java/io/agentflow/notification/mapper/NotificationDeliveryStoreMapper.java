package io.agentflow.notification.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcNotificationDeliveryStore 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface NotificationDeliveryStoreMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("recipientId") String recipientId,
            @Param("inboxId") String inboxId,
            @Param("channel") String channel,
            @Param("consentGeneration") Long consentGeneration,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("bindingId") String bindingId,
            @Param("destinationDigest") String destinationDigest,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("traceId") String traceId);

    /** 读取 suppressRevoked 所需的持久化事实。 */
    List<SqlRow> suppressRevoked(
            @Param("tenantId") String tenantId, @Param("recipient") String recipient);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("stamp") Timestamp stamp, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 get 所需的持久化事实。 */
    List<SqlRow> get(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId);

    /** 读取 ownsMessage 所需的持久化事实。 */
    List<Boolean> ownsMessage(
            @Param("inboxId") String inboxId,
            @Param("tenantId") String tenantId,
            @Param("recipient") String recipient);

    /** 更新 save 所需的持久化事实。 */
    int save(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("attempts") Integer attempts,
            @Param("cycleAttempts") Integer cycleAttempts,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("leaseToken") String leaseToken,
            @Param("errorCode") String errorCode,
            @Param("changedAt") Timestamp changedAt,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion);

    /** 新增 history 所需的持久化事实。 */
    int history(
            @Param("deliveryId") String deliveryId,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("cycleAttempts") Integer cycleAttempts,
            @Param("errorCode") String errorCode,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("occurredAt") Timestamp occurredAt);

    /** 执行 find 的条件查询。 */
    List<SqlRow> findQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);

    /** 按 search 的筛选条件执行数据库查询。 */
    List<SqlRow> searchQuery(
            @Param("hasChannel") boolean hasChannel,
            @Param("hasStatus") boolean hasStatus,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);

    /** 按 history 的筛选条件执行数据库查询。 */
    List<SqlRow> historyQuery(
            @Param("hasBeforeVersion") boolean hasBeforeVersion,
            @Param("parameters") Object[] parameters);
}
