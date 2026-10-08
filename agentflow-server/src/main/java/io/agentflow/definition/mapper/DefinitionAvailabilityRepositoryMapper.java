package io.agentflow.definition.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcDefinitionAvailabilityRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DefinitionAvailabilityRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId,
            @Param("revision") Long revision,
            @Param("previousEnabled") Boolean previousEnabled,
            @Param("startEnabled") Boolean startEnabled,
            @Param("changedBy") String changedBy,
            @Param("authorizedRole") String authorizedRole,
            @Param("changedAt") Timestamp changedAt,
            @Param("reason") String reason);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(
            @Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId,
            @Param("beforeRevision") Long beforeRevision,
            @Param("limit") Integer limit);
}
