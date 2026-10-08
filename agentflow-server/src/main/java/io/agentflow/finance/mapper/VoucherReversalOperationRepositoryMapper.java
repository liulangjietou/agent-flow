package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcVoucherReversalOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface VoucherReversalOperationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("operationId") String operationId,
            @Param("originalVersion") Long originalVersion,
            @Param("preparationVersion") Long preparationVersion,
            @Param("inputJson") String inputJson,
            @Param("commandDigest") String commandDigest,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("activeOperationId") String activeOperationId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("highestRevision") Long highestRevision,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson,
            @Param("digest") String digest);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 forOriginal 所需的持久化事实。 */
    List<SqlRow> forOriginal(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 retirement 所需的持久化事实。 */
    List<SqlRow> retirement(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 retirements 所需的持久化事实。 */
    List<SqlRow> retirements(@Param("tenant") String tenant, @Param("original") String original);

    /** 新增 retire 所需的持久化事实。 */
    int retire(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("operationId") String operationId,
            @Param("reversalId") String reversalId,
            @Param("originalVersion") Long originalVersion,
            @Param("releasedVersion") Long releasedVersion,
            @Param("reversalVersion") Long reversalVersion,
            @Param("stoppedVersion") Long stoppedVersion,
            @Param("basis") String basis,
            @Param("retiredBy") String retiredBy,
            @Param("retiredAt") Timestamp retiredAt,
            @Param("retirementJson") String retirementJson);

    /** 更新 retire 所需的持久化事实。 */
    int retire2(
            @Param("retiredAt") Timestamp retiredAt,
            @Param("tenantId") String tenantId,
            @Param("reversalId") String reversalId,
            @Param("stoppedVersion") Long stoppedVersion,
            @Param("operationId") String operationId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("reversalId") String reversalId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);
}
