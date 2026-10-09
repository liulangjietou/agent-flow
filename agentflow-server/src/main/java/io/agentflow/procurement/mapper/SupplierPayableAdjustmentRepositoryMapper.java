package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPayableAdjustmentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPayableAdjustmentRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("preparationVersion") Long preparationVersion,
            @Param("paymentId") String paymentId,
            @Param("returnVersion") Long returnVersion,
            @Param("reservationId") String reservationId,
            @Param("commandJson") String commandJson,
            @Param("commandDigest") String commandDigest,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("activePaymentId") String activePaymentId);

    /** 读取 resolve 所需的持久化事实。 */
    List<String> resolve(
            @Param("tenantId") String tenantId, @Param("adjustmentId") String adjustmentId);

    /** 新增 resolve 所需的持久化事实。 */
    int resolve2(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("adjustmentId") String adjustmentId,
            @Param("disputedVersion") Long disputedVersion,
            @Param("resolvedVersion") Long resolvedVersion,
            @Param("outcome") String outcome,
            @Param("resolvedBy") String resolvedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("stateJson") String stateJson);

    /** 读取 latestResolution 所需的持久化事实。 */
    List<SqlRow> latestResolution(@Param("tenant") String tenant, @Param("id") String id);

    /** 更新 persist 所需的持久化事实。 */
    int persist(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("dispatches") Integer dispatches,
            @Param("highestRevision") Long highestRevision,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("commandJson") String commandJson,
            @Param("digest") String digest);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("paymentId") String paymentId,
            @Param("limit") Integer limit);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page2(
            @Param("tenant") String tenant,
            @Param("paymentId") String paymentId,
            @Param("createdAt") Timestamp createdAt,
            @Param("expectedCreatedAt") Timestamp expectedCreatedAt,
            @Param("id") String id,
            @Param("limit") Integer limit);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 新增 retire 所需的持久化事实。 */
    int retire(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("paymentId") String paymentId,
            @Param("operationVersion") Long operationVersion,
            @Param("basis") String basis,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("stateJson") String stateJson);

    /** 更新 retire 所需的持久化事实。 */
    int retire2(
            @Param("operationVersion") Long operationVersion,
            @Param("tenant") String tenant,
            @Param("operationId") String operationId,
            @Param("version") Long version);

    /** 读取 retirement 所需的持久化事实。 */
    List<SqlRow> retirement(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 awaitingLocalCompletion 所需的持久化事实。 */
    List<SqlRow> awaitingLocalCompletion();

    /** 更新 complete 所需的持久化事实。 */
    int complete(
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("stateJson") String stateJson,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion);

    /** 读取 requireCompletion 所需的持久化事实。 */
    List<SqlRow> requireCompletion(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("completedVersion") Long completedVersion);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 读取 resolutionHistory 的完整历史修订。 */
    List<SqlRow> resolutionHistoryRows(@Param("parameters") Object[] parameters);
}
