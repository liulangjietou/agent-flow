package io.agentflow.procurement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SupplierPayableReturnGuard 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface SupplierPayableReturnGuardMapper {
    /** 读取 lock 所需的持久化事实。 */
    List<String> lock(
            @Param("tenant") String tenant,
            @Param("tenantId") String tenantId,
            @Param("legalEntityId") String legalEntityId,
            @Param("supplierReference") String supplierReference,
            @Param("payableReference") String payableReference);

    /** 执行 blocked 的条件查询。 */
    List<Integer> blockedQuery(@Param("parameters") Object[] parameters);
}
