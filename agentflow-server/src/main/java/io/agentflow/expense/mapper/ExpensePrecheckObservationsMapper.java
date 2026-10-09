package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * ExpensePrecheckObservations 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePrecheckObservationsMapper {
    /** 读取 dependencies 所需的持久化事实。 */
    List<SqlRow> dependencies(@Param("parameters") Map<String, ?> parameters);

    /** 读取 dependencies 所需的持久化事实。 */
    List<SqlRow> dependencies2(@Param("parameters") Map<String, ?> parameters);
}
