package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseResourceAdjustmentPreparationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseResourceAdjustmentPreparationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId,
            @Param("settlementVersion") Long settlementVersion,
            @Param("consumptionId") String consumptionId,
            @Param("consumedVersion") Long consumedVersion,
            @Param("accrualReversalId") String accrualReversalId,
            @Param("paymentReturnsVersion") Long paymentReturnsVersion,
            @Param("paymentVoucherId") String paymentVoucherId,
            @Param("paymentVoucherVersion") Long paymentVoucherVersion,
            @Param("paymentVoucherReversalId") String paymentVoucherReversalId,
            @Param("requestedBy") String requestedBy,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(
            @Param("tenant") String tenant,
            @Param("report") String report,
            @Param("actor") String actor);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("preparationId") String preparationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
