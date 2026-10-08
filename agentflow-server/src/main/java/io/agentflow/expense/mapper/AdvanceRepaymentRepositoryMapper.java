package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcAdvanceRepaymentRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface AdvanceRepaymentRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("advanceId") String advanceId,
            @Param("advanceVersion") Long advanceVersion,
            @Param("checkId") String checkId,
            @Param("checkVersion") Long checkVersion,
            @Param("legalEntityId") String legalEntityId,
            @Param("receiptReference") String receiptReference,
            @Param("channel") String channel,
            @Param("transactionReference") String transactionReference,
            @Param("voucherReference") String voucherReference,
            @Param("entryReference") String entryReference,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("recordedBy") String recordedBy,
            @Param("recordedAt") Timestamp recordedAt,
            @Param("stateJson") String stateJson);

    /** 读取 forReceipt 所需的持久化事实。 */
    List<SqlRow> forReceipt(
            @Param("tenant") String tenant,
            @Param("legalEntity") String legalEntity,
            @Param("reference") String reference);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 list 所需的持久化事实。 */
    List<Timestamp> list(
            @Param("tenant") String tenant,
            @Param("advanceId") String advanceId,
            @Param("before") String before);

    /** 读取 findRecorded 所需的持久化事实。 */
    List<SqlRow> findRecorded(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);

    /** 按 list 的筛选条件执行数据库查询。 */
    List<SqlRow> listQuery(
            @Param("hasCursor") boolean hasCursor, @Param("parameters") Object[] parameters);
}
