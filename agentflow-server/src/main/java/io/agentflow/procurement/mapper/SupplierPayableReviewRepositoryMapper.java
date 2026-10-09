package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPayableReviewRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPayableReviewRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("requestId") String requestId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") Object employeeId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationVersion") Long applicationVersion,
            @Param("requestVersion") Long requestVersion,
            @Param("reservationId") String reservationId,
            @Param("requestedBy") String requestedBy,
            @Param("requestedAt") Timestamp requestedAt,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("activeRequestId") String activeRequestId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("checkedAt") Timestamp checkedAt,
            @Param("activeRequest") String activeRequest,
            @Param("consumedAuthorizationId") String consumedAuthorizationId,
            @Param("tenant") Object tenant,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("requestId") String requestId,
            @Param("finance") String finance);

    /** 读取 forAuthorization 所需的持久化事实。 */
    List<SqlRow> forAuthorization(
            @Param("tenant") String tenant, @Param("authorizationId") String authorizationId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") Object tenantId,
            @Param("reviewId") String reviewId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
