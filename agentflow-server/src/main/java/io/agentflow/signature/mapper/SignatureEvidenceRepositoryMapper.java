package io.agentflow.signature.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSignatureEvidenceRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SignatureEvidenceRepositoryMapper {
    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("operationId") String operationId,
            @Param("evidenceDigest") String evidenceDigest,
            @Param("receiptDigest") String receiptDigest,
            @Param("providerRevision") Long providerRevision,
            @Param("status") String status,
            @Param("acceptedVersion") Long acceptedVersion,
            @Param("verifiedAt") Timestamp verifiedAt,
            @Param("evidenceJson") String evidenceJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenant") String tenant,
            @Param("operationId") String operationId,
            @Param("evidenceDigest") String evidenceDigest);

    /** 读取 forReceipt 所需的持久化事实。 */
    List<SqlRow> forReceipt(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("digest") String digest,
            @Param("version") Long version);
}
