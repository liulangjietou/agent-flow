package io.agentflow.agent.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcInvoiceExtractionRunRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface InvoiceExtractionRunRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("id") String id,
            @Param("pageCount") Object pageCount,
            @Param("method") String method,
            @Param("targetDigest") String targetDigest,
            @Param("stateJson") String stateJson,
            @Param("stateJson2") String stateJson2,
            @Param("createdAt") Timestamp createdAt,
            @Param("traceId") String traceId,
            @Param("tenantId") String tenantId,
            @Param("invoiceId") String invoiceId,
            @Param("requestedBy") String requestedBy,
            @Param("originalId") String originalId,
            @Param("originalDigest") Object originalDigest,
            @Param("format") String format,
            @Param("originalBytes") Object originalBytes);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("version") Long version,
            @Param("stateJson") String stateJson,
            @Param("leaseUntil") Timestamp leaseUntil,
            @Param("invoiceId") String invoiceId,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("previous") String previous,
            @Param("contextJson") String contextJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 activeId 所需的持久化事实。 */
    List<String> activeId(@Param("tenant") String tenant, @Param("invoiceId") String invoiceId);

    /** 读取 due 所需的持久化事实。 */
    List<SqlRow> due(@Param("leaseUntil") Timestamp leaseUntil, @Param("BATCH_SIZE") Integer BATCH_SIZE);

    /** 读取 lease 所需的持久化事实。 */
    List<SqlRow> lease(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 page 所需的持久化事实。 */
    List<SqlRow> page(
            @Param("tenant") String tenant,
            @Param("invoiceId") String invoiceId,
            @Param("size") Integer size,
            @Param("offset") Long offset);

    /** 读取 page 所需的持久化事实。 */
    List<Long> page2(@Param("tenant") String tenant, @Param("invoiceId") String invoiceId);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("runId") String runId,
            @Param("runVersion") Long runVersion,
            @Param("status") String status,
            @Param("stateJson") String stateJson);

    /** 新增 append 所需的持久化事实。 */
    int append2(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("aggregateVersion") Long aggregateVersion,
            @Param("action") String action,
            @Param("actorId") String actorId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") Timestamp occurredAt);
}
