package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseSubmissionControlRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseSubmissionControlRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("roundNo") Integer roundNo,
            @Param("paperReceiptRequired") Boolean paperReceiptRequired,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("submittedAt") Timestamp submittedAt,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("employeeId") String employeeId,
            @Param("precheckId") String precheckId,
            @Param("submittedFinancialVersion") Long submittedFinancialVersion);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("inputJson") String inputJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
