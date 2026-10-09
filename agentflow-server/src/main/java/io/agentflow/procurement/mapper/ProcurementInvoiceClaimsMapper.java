package io.agentflow.procurement.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * JdbcProcurementInvoiceClaims 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ProcurementInvoiceClaimsMapper {
    /** 新增 recognize 所需的持久化事实。 */
    int recognize(
            @Param("tenantId") String tenantId,
            @Param("invoiceKey") String invoiceKey,
            @Param("procurementReservationId") String procurementReservationId,
            @Param("roundNo") Integer roundNo);

    /** 读取 origins 所需的持久化事实。 */
    List<SqlRow> origins(@Param("parameters") Map<String, ?> parameters);
}
