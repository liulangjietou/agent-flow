package io.agentflow.template.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcTemplateCopyRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface TemplateCopyRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("tenantId") String tenantId,
            @Param("definitionId") String definitionId,
            @Param("templateKey") String templateKey,
            @Param("templateVersion") Long templateVersion,
            @Param("copiedBy") String copiedBy,
            @Param("copiedAt") Timestamp copiedAt);

    /** 读取 findByTemplate 所需的持久化事实。 */
    List<SqlRow> findByTemplate(
            @Param("tenantId") String tenantId, @Param("templateKey") String templateKey);
}
