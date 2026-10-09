package io.agentflow.expense.reporting.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ExpenseReportingResources 的 SQL 映射。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseReportingResourcesMapper {

    /** 执行 candidates 的条件查询。 */
    List<SqlRow> candidatesQuery(
            @Param("matchAdvance") boolean matchAdvance, @Param("parameters") Object[] parameters);
}
