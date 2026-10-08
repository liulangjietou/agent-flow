package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpensePriorControlRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePriorControlRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("roundNo") Integer roundNo,
            @Param("submittedAt") Timestamp submittedAt,
            @Param("requiresApproval") Boolean requiresApproval,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("applicationId") String applicationId,
            @Param("financialVersion") Long financialVersion,
            @Param("applicationVersion") Long applicationVersion,
            @Param("currentRoundNo") Integer currentRoundNo,
            @Param("roundNo11") Integer roundNo11,
            @Param("definitionId") String definitionId,
            @Param("definitionVersion") Long definitionVersion);

    /** 新增 save 所需的持久化事实。 */
    int save2(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("requestId") String requestId,
            @Param("beforeVersion") Long beforeVersion,
            @Param("afterVersion") Object afterVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);

    /** 读取 restore 所需的持久化事实。 */
    List<SqlRow> restore(
            @Param("tenantId") String tenantId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo);
}
