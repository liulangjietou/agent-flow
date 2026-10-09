package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcAccountMappingRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AccountMappingRepositoryMapper {
    /** 读取 head 所需的持久化事实。 */
    List<SqlRow> head(
            @Param("entity") String entity,
            @Param("currency") String currency,
            @Param("tenant") String tenant);

    /** 新增 initializeScope 所需的持久化事实。 */
    int initializeScope(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("currency") String currency,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("expectedCurrency") String expectedCurrency);

    /** 读取 draft 所需的持久化事实。 */
    List<SqlRow> draft(@Param("tenant") String tenant, @Param("key") String key);

    /** 新增 saveDraft 所需的持久化事实。 */
    int saveDraft(
            @Param("tenantId") String tenantId,
            @Param("mappingKey") String mappingKey,
            @Param("id") String id,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("name") String name,
            @Param("revision") Long revision,
            @Param("publishedVersion") Long publishedVersion,
            @Param("publishedDraftRevision") Long publishedDraftRevision,
            @Param("stateJson") String stateJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 saveDraft 所需的持久化事实。 */
    int saveDraft2(
            @Param("name") String name,
            @Param("revision") Long revision,
            @Param("stateJson") String stateJson,
            @Param("userId") String userId,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("expectedRevision") Long expectedRevision,
            @Param("publishedVersion") Long publishedVersion);

    /** 新增 saveDraft 所需的持久化事实。 */
    int saveDraft3(
            @Param("tenantId") String tenantId,
            @Param("mappingId") String mappingId,
            @Param("revision") Long revision,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("name") String name,
            @Param("definitionJson") String definitionJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("comment") String comment);

    /** 更新 publish 所需的持久化事实。 */
    int publish(
            @Param("version") Long version,
            @Param("revision") Long revision,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("key") String key,
            @Param("expectedRevision") Long expectedRevision,
            @Param("publishedVersion") Long publishedVersion);

    /** 新增 publish 所需的持久化事实。 */
    int publish2(
            @Param("tenantId") String tenantId,
            @Param("mappingId") String mappingId,
            @Param("version") Long version,
            @Param("draftRevision") Long draftRevision,
            @Param("categoryRevision") Long categoryRevision,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("name") String name,
            @Param("targetDigest") String targetDigest,
            @Param("definitionDigest") String definitionDigest,
            @Param("stateJson") String stateJson,
            @Param("publishedBy") String publishedBy,
            @Param("publishedAt") Timestamp publishedAt,
            @Param("comment") String comment);

    /** 新增 publish 所需的持久化事实。 */
    int publish3(
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("revision") Long revision,
            @Param("mappingId") String mappingId,
            @Param("mappingVersion") Long mappingVersion,
            @Param("activatedBy") String activatedBy,
            @Param("activatedAt") Timestamp activatedAt,
            @Param("comment") String comment);

    /** 更新 publish 所需的持久化事实。 */
    int publish4(
            @Param("activeRevision") Long activeRevision,
            @Param("mappingId") String mappingId,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("expectedActiveRevision") Long expectedActiveRevision);

    /** 读取 version 所需的持久化事实。 */
    List<SqlRow> version(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 versions 所需的持久化事实。 */
    List<SqlRow> versions(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("before") Long before,
            @Param("limit") Integer limit);

    /** 读取 draftRevision 所需的持久化事实。 */
    List<SqlRow> draftRevision(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("revision") Long revision);

    /** 读取 activations 所需的持久化事实。 */
    List<SqlRow> activations(
            @Param("tenant") String tenant,
            @Param("entity") String entity,
            @Param("currency") String currency,
            @Param("before") Long before,
            @Param("limit") Integer limit);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery(
            @Param("condition0") boolean condition0,
            @Param("condition1") boolean condition1,
            @Param("parameters") Object[] parameters);
}
