package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * JdbcSupplierPaymentReturnRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPaymentReturnRepositoryMapper {
    /** 新增 register 所需的持久化事实。 */
    int register(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("paymentId") String paymentId,
            @Param("beforeVersion") Long beforeVersion,
            @Param("returnVersion") Long returnVersion,
            @Param("checkId") String checkId,
            @Param("checkVersion") Long checkVersion,
            @Param("outcome") String outcome,
            @Param("registeredBy") String registeredBy,
            @Param("observedAt") Timestamp observedAt,
            @Param("registeredAt") Timestamp registeredAt,
            @Param("stateJson") String stateJson);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenant") String tenant, @Param("id") String id);

    /** 读取 history 所需的持久化事实。 */
    List<SqlRow> history(@Param("tenant") String tenant, @Param("paymentId") String paymentId);

    /** 执行 page 的条件查询。 */
    List<SqlRow> pageQuery(
            @Param("condition0") boolean condition0, @Param("parameters") Object[] parameters);
}
