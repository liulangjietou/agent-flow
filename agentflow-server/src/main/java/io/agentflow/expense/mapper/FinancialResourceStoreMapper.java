package io.agentflow.expense.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * FinancialResourceStore 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface FinancialResourceStoreMapper {
    /** 新增 create 所需的持久化事实。 */
    int create(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id,
            @Param("ownerId") String ownerId,
            @Param("sourceReference") String sourceReference,
            @Param("contextJson") String contextJson,
            @Param("stateJson") String stateJson);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("version") Long version,
            @Param("state") String state,
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id,
            @Param("ownerId") String ownerId,
            @Param("sourceReference") String sourceReference,
            @Param("context") String context,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll(@Param("parameters") Map<String, ?> parameters);

    /** 删除 replaceAmountUses 所需的持久化事实。 */
    int replaceAmountUses(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("id") String id);

    /** 新增 append 所需的持久化事实。 */
    int append(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("version") Long version,
            @Param("actorId") String actorId,
            @Param("operation") String operation,
            @Param("stateJson") String stateJson);

    /** 读取 insertAmountUses 所需的数据库事实。 */
    int insertAmountUses(@Param("rows") List<AmountUseRow> rows);

    /**
     * 金额占用行只表达本次真实批量写入的列，MyBatis 通过属性绑定每一行。
     *
     * @author owlzhangfq@gmail.com
     */
    record AmountUseRow(
            String tenantId,
            String resourceType,
            String resourceId,
            int sourceLine,
            String reportId,
            long roundNo,
            int reportLine,
            java.math.BigDecimal amount,
            String currency,
            String status) {}
}
