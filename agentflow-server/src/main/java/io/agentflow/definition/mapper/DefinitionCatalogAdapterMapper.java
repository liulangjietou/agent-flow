package io.agentflow.definition.mapper;

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
public interface DefinitionCatalogAdapterMapper {

    /** 按 search 的筛选条件执行数据库查询。 */
    List<SqlRow> searchQuery(
            @Param("hasText") boolean hasText,
            @Param("hasStatus") boolean hasStatus,
            @Param("hasStartEnabled") boolean hasStartEnabled,
            @Param("hasProcessKey") boolean hasProcessKey,
            @Param("hasVersion") boolean hasVersion,
            @Param("hasBeforeTime") boolean hasBeforeTime,
            @Param("parameters") Object[] parameters);
}
