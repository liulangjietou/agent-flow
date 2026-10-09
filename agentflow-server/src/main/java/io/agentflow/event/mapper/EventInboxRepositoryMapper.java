package io.agentflow.event.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcEventInboxRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface EventInboxRepositoryMapper {
    /** 读取 byEvent 所需的持久化事实。 */
    List<SqlRow> byEvent(
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("eventId") String eventId);

    /** 读取 get 所需的持久化事实。 */
    List<SqlRow> get(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<SqlRow> lock(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("sourceKey") String sourceKey,
            @Param("eventId") String eventId,
            @Param("applicationId") String applicationId,
            @Param("contractKey") String contractKey,
            @Param("contractVersion") Long contractVersion,
            @Param("inputJson") String inputJson,
            @Param("receivedAt") Timestamp receivedAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("failures") Integer failures,
            @Param("reason") String reason,
            @Param("errorCode") String errorCode,
            @Param("requestedBy") String requestedBy,
            @Param("requestReason") String requestReason,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson,
            @Param("receivedAt") Timestamp receivedAt);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("BATCH_SIZE") Integer BATCH_SIZE);

    /** 读取 findProcessInstance 所需的持久化事实。 */
    List<SqlRow> findProcessInstance(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(@Param("tenantId") String tenantId, @Param("limit") Integer limit);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page2(
            @Param("tenantId") String tenantId,
            @Param("receivedAt") Timestamp receivedAt,
            @Param("expectedReceivedAt") Timestamp expectedReceivedAt,
            @Param("beforeId") String beforeId,
            @Param("limit") Integer limit);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("inboxId") String inboxId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("occurredAt") Timestamp occurredAt);

    /** 执行 history 的条件查询。 */
    List<SqlRow> historyQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
