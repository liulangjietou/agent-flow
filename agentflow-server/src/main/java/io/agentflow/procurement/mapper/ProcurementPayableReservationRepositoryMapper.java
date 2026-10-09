package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcProcurementPayableReservationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ProcurementPayableReservationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("requestId") String requestId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("requestVersion") Long requestVersion,
            @Param("roundNo") Integer roundNo,
            @Param("legalEntityId") String legalEntityId,
            @Param("supplierReference") String supplierReference,
            @Param("payableReference") String payableReference,
            @Param("activeRequestId") String activeRequestId,
            @Param("activePayableReference") String activePayableReference,
            @Param("stateJson") String stateJson,
            @Param("heldAt") Timestamp heldAt);

    /** 更新 release 所需的持久化事实。 */
    int release(
            @Param("stateJson") String stateJson,
            @Param("releasedAt") Timestamp releasedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    /** 读取 complete 所需的持久化事实。 */
    List<SqlRow> complete(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("digest") String digest,
            @Param("expectedVersion") Long expectedVersion,
            @Param("commandJson") String commandJson,
            @Param("commandDigest") String commandDigest);

    /** 新增 complete 所需的持久化事实。 */
    int complete2(
            @Param("tenantId") String tenantId,
            @Param("reservationId") String reservationId,
            @Param("operationId") String operationId,
            @Param("operationVersion") Long operationVersion,
            @Param("paymentId") String paymentId,
            @Param("paymentVersion") Long paymentVersion,
            @Param("completedAt") Timestamp completedAt);

    /** 更新 complete 所需的持久化事实。 */
    int complete3(
            @Param("stateJson") String stateJson,
            @Param("settledAt") Timestamp settledAt,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("tenant") String tenant,
            @Param("expectedId") String expectedId);

    /** 更新 completeAdjustment 所需的持久化事实。 */
    int completeAdjustment(
            @Param("stateJson") String stateJson,
            @Param("completedAt") Timestamp completedAt,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("tenant") String tenant,
            @Param("expectedId") String expectedId,
            @Param("expectedStateJson") String expectedStateJson);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("requestId") String requestId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 requireCompletion 所需的持久化事实。 */
    List<SqlRow> requireCompletion(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reservationId") String reservationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
