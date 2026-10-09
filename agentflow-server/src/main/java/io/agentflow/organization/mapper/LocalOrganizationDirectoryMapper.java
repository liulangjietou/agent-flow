package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LocalOrganizationDirectory 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface LocalOrganizationDirectoryMapper {
    /** 读取 approvers 所需的持久化事实。 */
    List<String> approvers(@Param("tenantId") String tenantId);

    /** 读取 formOptions 所需的持久化事实。 */
    List<SqlRow> formOptions(@Param("tenantId") String tenantId);

    /** 读取 formOptions 所需的持久化事实。 */
    List<SqlRow> formOptions2(@Param("tenantId") String tenantId);

    /** 读取 options 所需的持久化事实。 */
    List<SqlRow> options(@Param("tenantId") String tenantId);

    /** 执行 options 的条件查询。 */
    List<SqlRow> optionsQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);

    /** 按 roleMembers 的筛选条件执行数据库查询。 */
    List<String> roleMembersQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);

    /** 按 memberCounts 的筛选条件执行数据库查询。 */
    List<SqlRow> memberCountsQuery(
            @Param("columnChoice") String columnChoice,
            @Param("condition1") boolean condition1,
            @Param("parameters") Object[] parameters);
}
