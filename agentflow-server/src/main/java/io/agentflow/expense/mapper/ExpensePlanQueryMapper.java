package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ExpensePlanQuery 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpensePlanQueryMapper {
    /** 读取 list 所需的持久化事实。 */
    List<Timestamp> list(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("before") String before);

    /** 按 list 的筛选条件执行数据库查询。 */
    List<SqlRow> listQuery(
            @Param("containsKeySelected") boolean containsKeySelected,
            @Param("hasBefore") boolean hasBefore,
            @Param("parameters") Object[] parameters);
}
