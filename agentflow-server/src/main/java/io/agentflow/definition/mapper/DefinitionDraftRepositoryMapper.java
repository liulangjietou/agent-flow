package io.agentflow.definition.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcDefinitionDraftRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DefinitionDraftRepositoryMapper {
    /** 更新 save 所需的持久化事实。 */
    int save(
            @Param("name") String name,
            @Param("persistedVersion") Object persistedVersion,
            @Param("revision") Long revision,
            @Param("status") String status,
            @Param("graphJson") String graphJson,
            @Param("schemaJson") String schemaJson,
            @Param("textsJson") String textsJson,
            @Param("startEnabled") Boolean startEnabled,
            @Param("status2") String status2,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") Long expectedRevision);

    /** 读取 save 所需的持久化事实。 */
    List<Integer> save2(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增 save 所需的持久化事实。 */
    int save3(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("processKey") String processKey,
            @Param("name") String name,
            @Param("version") Object version,
            @Param("revision") Long revision,
            @Param("status") String status,
            @Param("graphJson") String graphJson,
            @Param("formSchemaJson") String formSchemaJson,
            @Param("notificationTextsJson") String notificationTextsJson,
            @Param("startEnabled") Boolean startEnabled);

    /** 读取 nextVersion 所需的持久化事实。 */
    List<Long> nextVersion(@Param("tenantId") String tenantId, @Param("key") String key);

    /** 读取 findById 所需的持久化事实。 */
    List<SqlRow> findById(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 findPublished 所需的持久化事实。 */
    List<SqlRow> findPublished(
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("version") Long version);

    /** 读取 lockPublished 所需的持久化事实。 */
    List<SqlRow> lockPublished(
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("version") Long version);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll(@Param("tenantId") String tenantId);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll2(
            @Param("tenantId") String tenantId, @Param("toUpperCase") Object toUpperCase);
}
