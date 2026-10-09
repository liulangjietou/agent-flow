package io.agentflow.finance.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcPaymentDisputeResolutionRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface PaymentDisputeResolutionRepositoryMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("paymentId") String paymentId,
            @Param("disputedVersion") Long disputedVersion,
            @Param("resolvedVersion") Long resolvedVersion,
            @Param("outcome") String outcome,
            @Param("resolvedBy") String resolvedBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("resolvedAt") Timestamp resolvedAt,
            @Param("stateJson") String stateJson);

    /** 读取 latest 所需的持久化事实。 */
    List<SqlRow> latest(@Param("tenant") String tenant, @Param("paymentId") String paymentId);
}
