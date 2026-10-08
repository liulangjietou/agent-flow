package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseResourceAdjustmentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseResourceAdjustmentRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("preparationVersion") Long preparationVersion,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("activeReportId") String activeReportId,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt);

    /** 新增 retire 所需的持久化事实。 */
    int retire(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Long afterVersion,
            @Param("stoppedBudgetVersion") Long stoppedBudgetVersion,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("stateJson") String stateJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 active 所需的持久化事实。 */
    List<SqlRow> active(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("report") String report);

    /** 读取 ready 所需的持久化事实。 */
    List<SqlRow> ready();

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 retirement 所需的持久化事实。 */
    List<SqlRow> retirement(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revisions 所需的持久化事实。 */
    List<SqlRow> revisions(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 requireResourceEffects 所需的持久化事实。 */
    List<SqlRow> requireResourceEffects(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 更新 save 所需的持久化事实。 */
    int save(
            @Param("stateJson") String stateJson,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("reportId") String reportId,
            @Param("budgetReversalVersion") Long budgetReversalVersion,
            @Param("resourcesReversed") Boolean resourcesReversed,
            @Param("issue") String issue,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("adjustmentId") String adjustmentId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
