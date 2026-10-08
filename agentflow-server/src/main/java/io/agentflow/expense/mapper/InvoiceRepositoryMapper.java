package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * JdbcInvoiceRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface InvoiceRepositoryMapper {
    /** 读取 syncClaim 所需的持久化事实。 */
    List<SqlRow> syncClaim(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 syncClaim 所需的持久化事实。 */
    List<Integer> syncClaim2(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("version") Long version,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Object lineNo);

    /** 删除 syncClaim 所需的持久化事实。 */
    int syncClaim3(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 删除 syncClaim 所需的持久化事实。 */
    int syncClaim4(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 更新 syncClaim 所需的持久化事实。 */
    int syncClaim5(
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Integer lineNo,
            @Param("occupation") String occupation,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    /** 新增 syncClaim 所需的持久化事实。 */
    int syncClaim6(
            @Param("tenantId") String tenantId,
            @Param("invoiceKey") String invoiceKey,
            @Param("invoiceId") String invoiceId,
            @Param("reportId") String reportId,
            @Param("roundNo") Integer roundNo,
            @Param("lineNo") Integer lineNo,
            @Param("status") String status);
}
