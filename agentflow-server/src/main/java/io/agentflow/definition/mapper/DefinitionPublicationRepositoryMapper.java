package io.agentflow.definition.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcDefinitionPublicationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DefinitionPublicationRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId,
            @Param("processKey") String processKey,
            @Param("definitionVersion") Long definitionVersion,
            @Param("publishedBy") String publishedBy,
            @Param("authorizedRole") String authorizedRole,
            @Param("publishedAt") Timestamp publishedAt,
            @Param("changeNote") String changeNote,
            @Param("validationJson") String validationJson);

    /** 读取 findByDefinition 所需的持久化事实。 */
    List<SqlRow> findByDefinition(
            @Param("tenantId") String tenantId, @Param("definitionId") String definitionId);
}
