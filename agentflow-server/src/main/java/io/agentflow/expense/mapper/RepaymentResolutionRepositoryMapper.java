package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcRepaymentResolutionRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface RepaymentResolutionRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("advanceId") String advanceId,
            @Param("advanceVersion") Long advanceVersion,
            @Param("repaymentId") String repaymentId,
            @Param("checkId") String checkId,
            @Param("checkVersion") Long checkVersion,
            @Param("outcome") String outcome,
            @Param("resolvedBy") String resolvedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("stateJson") String stateJson);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("tenantId") String tenantId,
            @Param("repaymentId") String repaymentId,
            @Param("resolutionId") String resolutionId,
            @Param("legalEntityId") String legalEntityId,
            @Param("channel") String channel,
            @Param("transactionReference") Object transactionReference,
            @Param("voucherReference") Object voucherReference,
            @Param("entryReference") Object entryReference,
            @Param("amount") Object amount,
            @Param("currency") Object currency);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(@Param("tenant") String tenant, @Param("repaymentId") String repaymentId);

    /** 读取 returned 所需的持久化事实。 */
    List<SqlRow> returned(@Param("tenant") String tenant, @Param("repaymentId") String repaymentId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 revision 所需的持久化事实。 */
    List<SqlRow> revision(
            @Param("tenant") String tenant, @Param("id") String id, @Param("version") Long version);
}
