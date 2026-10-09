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
public interface AuditSearchAdapterMapper {

    /** 按 search 的筛选条件执行数据库查询。 */
    List<SqlRow> searchQuery(
            @Param("hasText") boolean hasText,
            @Param("hasActor") boolean hasActor,
            @Param("hasAction") boolean hasAction,
            @Param("hasSource") boolean hasSource,
            @Param("hasApplicationId") boolean hasApplicationId,
            @Param("hasOccurredFrom") boolean hasOccurredFrom,
            @Param("hasOccurredBefore") boolean hasOccurredBefore,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
