package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseSettlementRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseSettlementRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationVersion") Object applicationVersion,
            @Param("financialVersion") Object financialVersion,
            @Param("voucherOperationId") String voucherOperationId,
            @Param("paymentOperationId") String paymentOperationId,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("resourcesConsumed") Boolean resourcesConsumed,
            @Param("budgetOperationId") String budgetOperationId,
            @Param("issue") String issue,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("businessId") String businessId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("resourcesConsumed2") Boolean resourcesConsumed2);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("reportId") String reportId);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("version") Long version);

    /** 读取 pending 所需的持久化事实。 */
    List<SqlRow> pending();

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 按 recoveryCandidates 的筛选条件执行数据库查询。 */
    List<SqlRow> recoveryCandidatesQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
