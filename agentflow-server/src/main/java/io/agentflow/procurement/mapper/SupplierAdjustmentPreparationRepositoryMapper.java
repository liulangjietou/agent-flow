package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierAdjustmentPreparationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierAdjustmentPreparationRepositoryMapper {
    /** 读取 create 所需的持久化事实。 */
    List<Integer> create(@Param("tenant") Object tenant, @Param("paymentId") String paymentId);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("traceId") String traceId,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("paymentId") String paymentId,
            @Param("returnVersion") Object returnVersion,
            @Param("financeActor") String financeActor,
            @Param("accountingDate") Object accountingDate,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("activePaymentId") String activePaymentId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("owner") String owner,
            @Param("registered") String registered,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(@Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 requireRegistration 所需的持久化事实。 */
    List<SqlRow> requireRegistration(
            @Param("tenant") Object tenant, @Param("id") String id, @Param("version") Long version);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") Object tenantId,
            @Param("preparationId") String preparationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
