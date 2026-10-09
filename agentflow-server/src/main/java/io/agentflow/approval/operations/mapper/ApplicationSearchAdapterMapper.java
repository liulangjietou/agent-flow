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
public interface ApplicationSearchAdapterMapper {

    /** 按 search 的筛选条件执行数据库查询。 */
    List<SqlRow> searchQuery(
            @Param("itemsCount") int itemsCount,
            @Param("hasRoles") boolean hasRoles,
            @Param("restrictToParticipants") boolean restrictToParticipants,
            @Param("hasText") boolean hasText,
            @Param("hasStatus") boolean hasStatus,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasDefinitionVersion") boolean hasDefinitionVersion,
            @Param("hasApplicant") boolean hasApplicant,
            @Param("hasCreatedFrom") boolean hasCreatedFrom,
            @Param("hasCreatedBefore") boolean hasCreatedBefore,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
