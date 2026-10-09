package io.agentflow.finance.callback.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcPaymentCallbackRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentCallbackRepositoryMapper {
    /** 读取 byEvent 所需的持久化事实。 */
    List<SqlRow> byEvent(@Param("tenant") String tenant, @Param("event") String event);

    /** 读取 get 所需的持久化事实。 */
    List<SqlRow> get(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<SqlRow> lock(@Param("tenant") String tenant, @Param("id") String id);

    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("eventId") String eventId,
            @Param("payloadDigest") String payloadDigest,
            @Param("targetDigest") String targetDigest,
            @Param("paymentKind") String paymentKind,
            @Param("authorizationId") String authorizationId,
            @Param("commandDigest") String commandDigest,
            @Param("sourceRevision") Long sourceRevision,
            @Param("employeePaymentId") String employeePaymentId,
            @Param("supplierPaymentId") String supplierPaymentId,
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
            @Param("queryVersion") Long queryVersion,
            @Param("reason") String reason,
            @Param("requestedBy") String requestedBy,
            @Param("requestReason") String requestReason,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("eventId") String eventId,
            @Param("payloadDigest") String payloadDigest,
            @Param("targetDigest") String targetDigest,
            @Param("kind") String kind,
            @Param("authorizationId") String authorizationId,
            @Param("commandDigest") String commandDigest,
            @Param("sourceRevision") Long sourceRevision,
            @Param("receivedAt") Timestamp receivedAt);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(@Param("tenant") String tenant, @Param("limit") Integer limit);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page2(
            @Param("tenant") String tenant,
            @Param("receivedAt") Timestamp receivedAt,
            @Param("expectedReceivedAt") Timestamp expectedReceivedAt,
            @Param("id") String id,
            @Param("limit") Integer limit);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("callbackId") String callbackId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("occurredAt") Timestamp occurredAt);
}
