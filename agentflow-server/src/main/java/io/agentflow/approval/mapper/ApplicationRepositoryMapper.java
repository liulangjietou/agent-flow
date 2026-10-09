package io.agentflow.approval.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * JdbcApplicationRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface ApplicationRepositoryMapper {
    /** 新增 save 所需的持久化事实。 */
    int save(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("businessNo") String businessNo,
            @Param("processKey") String processKey,
            @Param("definitionVersion") Long definitionVersion,
            @Param("createdBy") String createdBy,
            @Param("title") String title,
            @Param("payloadJson") String payloadJson,
            @Param("status") String status,
            @Param("roundNo") Integer roundNo,
            @Param("version") Long version,
            @Param("formSchemaJson") String formSchemaJson,
            @Param("runtimeDefinitionId") String runtimeDefinitionId,
            @Param("notificationTextsJson") String notificationTextsJson,
            @Param("searchAmount") BigDecimal searchAmount,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId);

    /** 读取 findById 所需的持久化事实。 */
    List<SqlRow> findById(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 lockById 所需的持久化事实。 */
    List<SqlRow> lockById(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 读取 findByBusinessNo 所需的持久化事实。 */
    List<SqlRow> findByBusinessNo(
            @Param("tenantId") String tenantId, @Param("businessNo") String businessNo);

    /** 更新 update 所需的持久化事实。 */
    int update(
            @Param("status") String status,
            @Param("roundNo") Integer roundNo,
            @Param("version") Long version,
            @Param("title") String title,
            @Param("payloadJson") String payloadJson,
            @Param("searchAmount") BigDecimal searchAmount,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedVersion") Long expectedVersion);

    /** 读取 findAll 所需的持久化事实。 */
    List<SqlRow> findAll(@Param("tenantId") String tenantId);
}
