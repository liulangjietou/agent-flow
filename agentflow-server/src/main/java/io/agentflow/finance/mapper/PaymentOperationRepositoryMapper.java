package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcPaymentOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentOperationRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("inputJson") String inputJson,
            @Param("commandDigest") String commandDigest,
            @Param("stateJson") String stateJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("dispatches") Integer dispatches,
            @Param("highestRevision") Long highestRevision,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("tenantId") Object tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("inputJson") String inputJson,
            @Param("digest") String digest);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 读取 missingVoucherPreparations 所需的持久化事实。 */
    List<SqlRow> missingVoucherPreparations();

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("leaseUntil") Timestamp leaseUntil);

    /** 读取 missingAdvanceBalances 所需的持久化事实。 */
    List<SqlRow> missingAdvanceBalances();

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") Object tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson);

    /** 读取 firstSuccessfulRevision 的完整历史修订。 */
    List<SqlRow> firstSuccessfulRevisionRows(@Param("parameters") Object[] parameters);

    /** 读取 disputeEvidence 的完整历史修订。 */
    List<SqlRow> disputeEvidenceRows(@Param("parameters") Object[] parameters);
}
