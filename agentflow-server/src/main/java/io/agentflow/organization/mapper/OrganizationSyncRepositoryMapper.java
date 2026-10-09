package io.agentflow.organization.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcOrganizationSyncRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OrganizationSyncRepositoryMapper {
    /** 新增 register 所需的持久化事实。 */
    int register(
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("registeredBy") String registeredBy,
            @Param("registeredAt") Timestamp registeredAt);

    /** 读取 source 所需的持久化事实。 */
    List<SqlRow> source(@Param("tenant") String tenant);

    /** 读取 lockSource 所需的持久化事实。 */
    List<String> lockSource(@Param("tenant") String tenant);

    /** 更新 advance 所需的持久化事实。 */
    int advance(
            @Param("appliedRevision") Long appliedRevision,
            @Param("version") Long version,
            @Param("lastAppliedBatchId") String lastAppliedBatchId,
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("expectedVersion") Long expectedVersion,
            @Param("registeredBy") String registeredBy,
            @Param("registeredAt") Timestamp registeredAt,
            @Param("id") String id,
            @Param("receivedRevision") Long receivedRevision);

    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("requestedBy") String requestedBy,
            @Param("retryOf") String retryOf,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("afterRevision") Long afterRevision,
            @Param("retryOf2") String retryOf2,
            @Param("expectedId") String expectedId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("stateJson") String stateJson,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("revision") Long revision,
            @Param("expectedTenantId") String expectedTenantId,
            @Param("id") String id,
            @Param("previous") String previous,
            @Param("expectedVersion") Long expectedVersion,
            @Param("contextJson") String contextJson,
            @Param("status2") String status2);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lockBatch 所需的持久化事实。 */
    List<String> lockBatch(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil, @Param("SCAN_LIMIT") Integer SCAN_LIMIT);

    /** 读取 pendingId 所需的持久化事实。 */
    List<String> pendingId(@Param("tenant") String tenant, @Param("pendingTenantId") String pendingTenantId);

    /** 读取 transitions 所需的持久化事实。 */
    List<SqlRow> transitions(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("size") Integer size,
            @Param("offset") Long offset);

    /** 读取 page 所需的持久化事实。 */
    List<Long> page2(@Param("tenant") String tenant);

    /** 读取 binding 所需的持久化事实。 */
    List<SqlRow> binding(
            @Param("tenant") String tenant,
            @Param("kind") String kind,
            @Param("externalId") String externalId);

    /** 读取 bindingByLocal 所需的持久化事实。 */
    List<String> bindingByLocal(
            @Param("tenant") String tenant, @Param("kind") String kind, @Param("id") String id);

    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("kind") String kind,
            @Param("externalId") String externalId,
            @Param("localId") String localId,
            @Param("unitId") String unitId,
            @Param("personId") String personId,
            @Param("appointmentId") String appointmentId,
            @Param("localRevision") Long localRevision,
            @Param("sourceRevision") Long sourceRevision,
            @Param("appliedBatchId") String appliedBatchId,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 save 所需的持久化事实。 */
    int save2(
            @Param("localRevision") Long localRevision,
            @Param("sourceRevision") Long sourceRevision,
            @Param("version") Long version,
            @Param("appliedBatchId") String appliedBatchId,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("tenantId") String tenantId,
            @Param("sourceKey") String sourceKey,
            @Param("kind") String kind,
            @Param("externalId") String externalId,
            @Param("local") String local,
            @Param("expectedVersion") Long expectedVersion,
            @Param("beforeLocalRevision") Long beforeLocalRevision,
            @Param("beforeSourceRevision") Long beforeSourceRevision,
            @Param("beforeUpdatedAt") Timestamp beforeUpdatedAt);

    /** 新增 save 所需的持久化事实。 */
    int save3(
            @Param("tenantId") String tenantId,
            @Param("kind") String kind,
            @Param("externalId") String externalId,
            @Param("bindingVersion") Long bindingVersion,
            @Param("batchId") String batchId,
            @Param("snapshotJson") String snapshotJson);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("batchId") String batchId,
            @Param("batchVersion") Long batchVersion,
            @Param("status") String status,
            @Param("stateJson") String stateJson);

    /** 读取 bindingMatches 所需的数据库事实。 */
    List<Long> bindingMatches(
            @Param("kind") String kind,
            @Param("tenant") String tenant,
            @Param("batchId") String batchId,
            @Param("sourceKey") String sourceKey,
            @Param("sourceRevision") long sourceRevision,
            @Param("localId") String localId,
            @Param("localRevision") long localRevision);
}
