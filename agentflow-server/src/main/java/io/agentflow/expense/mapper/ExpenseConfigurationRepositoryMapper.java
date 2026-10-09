package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcExpenseConfigurationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ExpenseConfigurationRepositoryMapper {
    /** 新增 lock 所需的持久化事实。 */
    int lock(@Param("tenantId") String tenantId);

    /** 新增 saveCategories 所需的持久化事实。 */
    int saveCategories(
            @Param("tenantId") String tenantId,
            @Param("revision") Long revision,
            @Param("categoryCount") Integer categoryCount,
            @Param("stateJson") String stateJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("comment") String comment);

    /** 更新 saveCategories 所需的持久化事实。 */
    int saveCategories2(
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("categoryRevision") Long categoryRevision);

    /** 读取 draft 所需的持久化事实。 */
    List<SqlRow> draft(@Param("tenant") String tenant, @Param("key") String key);

    /** 新增 saveDraft 所需的持久化事实。 */
    int saveDraft(
            @Param("tenantId") String tenantId,
            @Param("policyKey") String policyKey,
            @Param("id") String id,
            @Param("name") String name,
            @Param("revision") Long revision,
            @Param("publishedVersion") Long publishedVersion,
            @Param("publishedDraftRevision") Long publishedDraftRevision,
            @Param("stateJson") String stateJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 saveDraft 所需的持久化事实。 */
    int saveDraft2(
            @Param("definition") String definition,
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
            @Param("policyId") String policyId,
            @Param("revision") Long revision,
            @Param("name") String name,
            @Param("definitionJson") String definitionJson,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("comment") String comment);

    /** 更新 publish 所需的持久化事实。 */
    int publish(
            @Param("version") Long version,
            @Param("draftRevision") Long draftRevision,
            @Param("stateJson") String stateJson,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("revision") Long revision,
            @Param("publishedVersion") Long publishedVersion);

    /** 新增 publish 所需的持久化事实。 */
    int publish2(
            @Param("tenantId") String tenantId,
            @Param("policyId") String policyId,
            @Param("version") Long version,
            @Param("draftRevision") Long draftRevision,
            @Param("categoryRevision") Long categoryRevision,
            @Param("name") String name,
            @Param("stateJson") String stateJson,
            @Param("publishedBy") String publishedBy,
            @Param("publishedAt") Timestamp publishedAt,
            @Param("comment") String comment);

    /** 新增 publish 所需的持久化事实。 */
    int publish3(
            @Param("tenantId") String tenantId,
            @Param("revision") Long revision,
            @Param("policyId") String policyId,
            @Param("policyVersion") Long policyVersion,
            @Param("activatedBy") String activatedBy,
            @Param("activatedAt") Timestamp activatedAt,
            @Param("comment") String comment);

    /** 更新 publish 所需的持久化事实。 */
    int publish4(
            @Param("activeRevision") Long activeRevision,
            @Param("policyId") String policyId,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("expectedActiveRevision") Long expectedActiveRevision,
            @Param("categoryRevision") Long categoryRevision);

    /** 读取 version 所需的持久化事实。 */
    List<SqlRow> version(
            @Param("tenant") String tenant,
            @Param("policyId") String policyId,
            @Param("version") Long version);

    /** 读取 list 所需的持久化事实。 */
    List<SqlRow> list(
            @Param("tenant") String tenant,
            @Param("policyKey") String policyKey,
            @Param("limit") Integer limit);

    /** 读取 versions 所需的持久化事实。 */
    List<SqlRow> versions(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("before") Long before,
            @Param("limit") Integer limit);

    /** 读取 categoryVersions 所需的持久化事实。 */
    List<SqlRow> categoryVersions(
            @Param("tenant") String tenant,
            @Param("before") Long before,
            @Param("limit") Integer limit);

    /** 读取 categoryRevision 所需的持久化事实。 */
    List<SqlRow> categoryRevision(@Param("tenant") String tenant, @Param("revision") Long revision);

    /** 读取 draftRevision 所需的持久化事实。 */
    List<SqlRow> draftRevision(
            @Param("tenant") String tenant,
            @Param("id") String id,
            @Param("revision") Long revision);

    /** 读取 activations 所需的持久化事实。 */
    List<SqlRow> activations(
            @Param("tenant") String tenant,
            @Param("before") Long before,
            @Param("limit") Integer limit);

    /** 执行 heads 的条件查询。 */
    List<SqlRow> headsQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
