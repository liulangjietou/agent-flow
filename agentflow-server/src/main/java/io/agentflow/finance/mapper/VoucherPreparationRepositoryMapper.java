package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcVoucherPreparationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface VoucherPreparationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("kind") String kind,
            @Param("applicationVersion") Object applicationVersion,
            @Param("businessVersion") Object businessVersion,
            @Param("employeeId") String employeeId,
            @Param("attemptNo") Long attemptNo,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("activeApplicationId") String activeApplicationId,
            @Param("createdAt") Timestamp createdAt,
            @Param("paymentOperationId") String paymentOperationId,
            @Param("paymentVersion") Object paymentVersion,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("applicationId") String applicationId,
            @Param("operationId") String operationId,
            @Param("startedAt") Timestamp startedAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("completedAt") Timestamp completedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("applicationId") String applicationId,
            @Param("round") Integer round,
            @Param("kind") String kind);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("preparationId") String preparationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
