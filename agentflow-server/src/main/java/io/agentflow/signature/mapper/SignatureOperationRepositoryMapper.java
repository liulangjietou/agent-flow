package io.agentflow.signature.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSignatureOperationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SignatureOperationRepositoryMapper {
    /** 读取 create 所需的持久化事实。 */
    List<SqlRow> create(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("applicationVersion") Object applicationVersion,
            @Param("processKey") Object processKey,
            @Param("definitionVersion") Object definitionVersion);

    /** 读取 create 所需的持久化事实。 */
    List<Integer> create2(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("definitionVersion") Object definitionVersion);

    /** 读取 create 所需的持久化事实。 */
    List<Integer> create3(
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo);

    /** 新增 create 所需的持久化事实。 */
    int create4(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("applicationId") String applicationId,
            @Param("roundNo") Integer roundNo,
            @Param("requestDigest") String requestDigest,
            @Param("targetDigest") String targetDigest,
            @Param("inputJson") String inputJson,
            @Param("stateJson") String stateJson,
            @Param("authorizedAt") Timestamp authorizedAt,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("pollAt") Timestamp pollAt,
            @Param("traceId") String traceId);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("status") String status,
            @Param("attempts") Integer attempts,
            @Param("stateJson") String stateJson,
            @Param("updatedAt") Timestamp updatedAt,
            @Param("nextAttemptAt") Timestamp nextAttemptAt,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("pollAt") Timestamp pollAt,
            @Param("terminal") Integer terminal,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("digest") String digest,
            @Param("targetDigest") String targetDigest);

    /** 新增 update 所需的持久化事实。 */
    int update2(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("documentId") String documentId,
            @Param("contentId") String contentId,
            @Param("byteSize") Long byteSize,
            @Param("sha256") String sha256,
            @Param("receiptDigest") String receiptDigest,
            @Param("createdVersion") Long createdVersion);

    /** 更新 markArtifactReady 所需的持久化事实。 */
    int markArtifactReady(
            @Param("savedAt") Timestamp savedAt,
            @Param("version") Long version,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("documentId") String documentId,
            @Param("contentId") String contentId,
            @Param("size") Long size,
            @Param("sha256") String sha256);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("pollAt") Timestamp pollAt, @Param("DUE_LIMIT") Integer DUE_LIMIT);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("afterVersion") Long afterVersion,
            @Param("version") Long version,
            @Param("limit") Integer limit);

    /** 读取 resultFiles 所需的持久化事实。 */
    List<SqlRow> resultFiles(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version);

    /** 新增 insertSource 所需的持久化事实。 */
    int insertSource(
            @Param("id") String id,
            @Param("roundNo") Integer roundNo,
            @Param("tenantId") String tenantId,
            @Param("applicationId") String applicationId,
            @Param("expectedRoundNo") Integer expectedRoundNo,
            @Param("attachmentId") String attachmentId,
            @Param("contentId") String contentId,
            @Param("fieldPath") String fieldPath,
            @Param("filename") String filename,
            @Param("size") Long size,
            @Param("sha256") String sha256);

    /** 读取 read 所需的持久化事实。 */
    List<SqlRow> read(@Param("tenantId") Object tenantId, @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("occurredAt") Timestamp occurredAt);

    /** 读取 findRows 所需的数据库事实。 */
    List<SqlRow> findRows(@Param("parameters") Object[] parameters);

    /** 读取 lockRows 所需的数据库事实。 */
    List<SqlRow> lockRows(@Param("parameters") Object[] parameters);

    /** 读取 forRoundRows 所需的数据库事实。 */
    List<SqlRow> forRoundRows(@Param("parameters") Object[] parameters);

    /** 读取 forRoundRows2 所需的数据库事实。 */
    List<SqlRow> forRoundRows2(@Param("parameters") Object[] parameters);
}
