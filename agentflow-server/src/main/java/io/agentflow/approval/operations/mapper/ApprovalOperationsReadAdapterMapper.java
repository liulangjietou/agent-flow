package io.agentflow.approval.operations.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 映射。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApprovalOperationsReadAdapterMapper {

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery2(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery3(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery4(
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization2") boolean emptyOrganization2,
            @Param("parameters") Object[] parameters);

    /** 按 read 的筛选条件执行数据库查询。 */
    List<SqlRow> readQuery5(
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization2") boolean emptyOrganization2,
            @Param("parameters") Object[] parameters);

    /** 读取 slaRows 所需的数据库事实。 */
    List<SqlRow> slaRows(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);

    /** 读取 notificationsRows 所需的数据库事实。 */
    List<SqlRow> notificationsRows(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);

    /** 读取 agentRows 所需的数据库事实。 */
    List<SqlRow> agentRows(
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("parameters") Object[] parameters);
}
