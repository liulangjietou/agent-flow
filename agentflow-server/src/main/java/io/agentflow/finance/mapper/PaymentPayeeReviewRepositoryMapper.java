package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcPaymentPayeeReviewRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentPayeeReviewRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("originalAuthorizationId") String originalAuthorizationId,
            @Param("originalAuthorizationVersion") Long originalAuthorizationVersion,
            @Param("voucherOperationId") String voucherOperationId,
            @Param("voucherVersion") Long voucherVersion,
            @Param("requestedBy") String requestedBy,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("checkedAt") Timestamp checkedAt,
            @Param("validUntil") Timestamp validUntil,
            @Param("consumedAuthorizationId") String consumedAuthorizationId,
            @Param("issue") String issue,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("originalAuthorizationId") String originalAuthorizationId,
            @Param("finance") String finance);

    /** 读取 forAuthorization 所需的持久化事实。 */
    List<SqlRow> forAuthorization(
            @Param("tenant") String tenant, @Param("authorizationId") String authorizationId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reviewId") String reviewId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
