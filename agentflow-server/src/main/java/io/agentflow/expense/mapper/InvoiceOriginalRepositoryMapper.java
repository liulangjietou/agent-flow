package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * JdbcInvoiceOriginalRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface InvoiceOriginalRepositoryMapper {
    /** 新增 reserveCapacity 所需的持久化事实。 */
    int reserveCapacity(
            @Param("tenant") String tenant,
            @Param("owner") String owner,
            @Param("tenantId") String tenantId,
            @Param("ownerId") String ownerId);

    /** 更新 reserveCapacity 所需的持久化事实。 */
    int reserveCapacity2(
            @Param("bytes") Long bytes,
            @Param("tenant") String tenant,
            @Param("owner") String owner,
            @Param("usedBytes") Long usedBytes,
            @Param("maximumUploads") Integer maximumUploads);

    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("invoiceId") String invoiceId,
            @Param("ownerId") String ownerId,
            @Param("filename") String filename,
            @Param("byteSize") Long byteSize,
            @Param("sha256") String sha256,
            @Param("format") String format,
            @Param("status") String status,
            @Param("createdAt") Timestamp createdAt);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("invoiceId") String invoiceId);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll(@Param("parameters") Map<String, ?> parameters);

    /** 读取 lock 所需的持久化事实。 */
    List<SqlRow> lock(@Param("tenant") String tenant, @Param("invoiceId") String invoiceId);

    /** 更新 updateStatus 所需的持久化事实。 */
    int updateStatus(
            @Param("status") String status,
            @Param("tenantId") String tenantId,
            @Param("invoiceId") String invoiceId);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);

    /** 执行 list 的条件查询。 */
    List<SqlRow> listQuery2(
            @Param("firstPage") boolean firstPage, @Param("parameters") Object[] parameters);
}
