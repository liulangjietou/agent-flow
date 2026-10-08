package io.agentflow.approval.workspace.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcWorkspaceReadAdapter 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface WorkspaceReadAdapterMapper {
    /** 读取 isParticipant 所需的持久化事实。 */
    List<Boolean> isParticipant(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("userId") String userId);

    /** 读取 participantsInRound 所需的持久化事实。 */
    List<SqlRow> participantsInRound(
            @Param("tenantId") String tenantId, @Param("applicationId") String applicationId);

    /** 按 applications 的筛选条件执行数据库查询。 */
    List<SqlRow> applicationsQuery(
            @Param("hasDrafts") boolean hasDrafts,
            @Param("emptyText") boolean emptyText,
            @Param("missingBeforeTime") boolean missingBeforeTime,
            @Param("parameters") Object[] parameters);

    /** 按 handled 的筛选条件执行数据库查询。 */
    List<SqlRow> handledQuery(
            @Param("hasAction") boolean hasAction,
            @Param("emptyText") boolean emptyText,
            @Param("missingBeforeTime") boolean missingBeforeTime,
            @Param("parameters") Object[] parameters);
}
