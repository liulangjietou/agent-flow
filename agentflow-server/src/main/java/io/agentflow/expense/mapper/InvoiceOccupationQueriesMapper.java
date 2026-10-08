package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * InvoiceOccupationQueries 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface InvoiceOccupationQueriesMapper {
    /** 读取 claims 所需的持久化事实。 */
    List<SqlRow> claims(@Param("parameters") Map<String, ?> parameters);
}
