package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcPaymentBatchRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentBatchRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("legalEntityId") String legalEntityId,
            @Param("currency") String currency,
            @Param("cashierId") String cashierId,
            @Param("itemCount") Integer itemCount,
            @Param("totalValue") BigDecimal totalValue,
            @Param("createdAt") Timestamp createdAt,
            @Param("stateJson") String stateJson);

    /** 新增 create 所需的持久化事实。 */
    int create2(
            @Param("tenantId") String tenantId,
            @Param("batchId") String batchId,
            @Param("lineNo") Integer lineNo,
            @Param("authorizationId") String authorizationId,
            @Param("authorizationVersion") Long authorizationVersion,
            @Param("requestId") String requestId);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 verifyOriginalRegistrations 所需的持久化事实。 */
    List<SqlRow> verifyOriginalRegistrations(
            @Param("tenantId") String tenantId, @Param("id") String id);

    /** 按 page 的筛选条件执行数据库查询。 */
    List<SqlRow> pageQuery(
            @Param("hasCursor") boolean hasCursor, @Param("parameters") Object[] parameters);
}
