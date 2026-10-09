package io.agentflow.approval.workspace.mapper;

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
public interface FlowablePendingTaskReadAdapterMapper {

    /** 按 read 的筛选条件执行数据库查询。 */
    List<Long> readQuery(
            @Param("itemsCount") int itemsCount,
            @Param("hasRoles") boolean hasRoles,
            @Param("proxyTaskIdsCount") int proxyTaskIdsCount,
            @Param("condition3") boolean condition3,
            @Param("conflictingTasksCount") int conflictingTasksCount,
            @Param("condition5") boolean condition5,
            @Param("matchAssigned") boolean matchAssigned,
            @Param("matchUnclaimed") boolean matchUnclaimed,
            @Param("matchDelegated") boolean matchDelegated,
            @Param("matchOverdue") boolean matchOverdue,
            @Param("matchPending") boolean matchPending,
            @Param("matchUnrecorded") boolean matchUnrecorded,
            @Param("hasRisk") boolean hasRisk,
            @Param("hasText") boolean hasText,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasApplicant") boolean hasApplicant,
            @Param("hasMinAmount") boolean hasMinAmount,
            @Param("hasMaxAmount") boolean hasMaxAmount,
            @Param("parameters") Object[] parameters);

    /** 读取 readRows 所需的数据库事实。 */
    List<SqlRow> readRows(
            @Param("itemsCount") int itemsCount,
            @Param("hasRoles") boolean hasRoles,
            @Param("proxyTaskIdsCount") int proxyTaskIdsCount,
            @Param("condition3") boolean condition3,
            @Param("conflictingTasksCount") int conflictingTasksCount,
            @Param("condition5") boolean condition5,
            @Param("matchAssigned") boolean matchAssigned,
            @Param("matchUnclaimed") boolean matchUnclaimed,
            @Param("matchDelegated") boolean matchDelegated,
            @Param("matchOverdue") boolean matchOverdue,
            @Param("matchPending") boolean matchPending,
            @Param("matchUnrecorded") boolean matchUnrecorded,
            @Param("hasRisk") boolean hasRisk,
            @Param("hasText") boolean hasText,
            @Param("emptyOrganization") boolean emptyOrganization,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasApplicant") boolean hasApplicant,
            @Param("hasMinAmount") boolean hasMinAmount,
            @Param("hasMaxAmount") boolean hasMaxAmount,
            @Param("hasAfterTime") boolean hasAfterTime,
            @Param("parameters") Object[] parameters);
}
