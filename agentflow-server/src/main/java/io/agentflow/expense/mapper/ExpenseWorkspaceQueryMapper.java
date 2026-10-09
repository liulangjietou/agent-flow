package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * ExpenseWorkspaceQuery 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseWorkspaceQueryMapper {
    /** 读取 reports 所需的持久化事实。 */
    List<Timestamp> reports(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("before") String before);

    /** 读取 resourceIds 所需的持久化事实。 */
    List<Integer> resourceIds(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("resourceType") String resourceType,
            @Param("before") String before);

    /** 执行 resourceIds 的条件查询。 */
    List<SqlRow> resourceIdsQuery(
            @Param("missingBefore") boolean missingBefore,
            @Param("parameters") Object[] parameters);

    /** 按 reports 的筛选条件执行数据库查询。 */
    List<SqlRow> reportsQuery(
            @Param("containsKeySelected") boolean containsKeySelected,
            @Param("hasBefore") boolean hasBefore,
            @Param("parameters") Object[] parameters);
}
