package io.agentflow.expense.reporting.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ExpenseReportingOperations 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseReportingOperationsMapper {
    /** 读取 arrival 所需的持久化事实。 */
    List<String> arrival(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 backlog 所需的持久化事实。 */
    List<SqlRow> backlog(@Param("tenant") String tenant);

    /** 读取 backlog 所需的持久化事实。 */
    List<SqlRow> backlog2(@Param("tenant") String tenant);
}
