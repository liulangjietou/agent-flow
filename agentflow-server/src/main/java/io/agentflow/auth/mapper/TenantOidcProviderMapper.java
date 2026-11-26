package io.agentflow.auth.mapper;

import io.agentflow.mybatis.SqlRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 多租户动态 OIDC 提供方配置 SQL 映射。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface TenantOidcProviderMapper {
    /** 列出本租户全部 OIDC 提供方配置。 */
    List<SqlRow> listByTenant(@Param("tenantId") String tenantId);

    /** 按租户与提供方键查询配置。 */
    List<SqlRow> findByKey(@Param("tenantId") String tenantId, @Param("providerKey") String providerKey);

    /** 按租户与标识查询配置。 */
    List<SqlRow> findById(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 新增提供方配置。 */
    int insert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("providerKey") String providerKey,
            @Param("displayName") String displayName,
            @Param("issuerUri") String issuerUri,
            @Param("clientId") String clientId,
            @Param("clientSecret") String clientSecret,
            @Param("scopes") String scopes,
            @Param("enabled") boolean enabled,
            @Param("updatedBy") String updatedBy,
            @Param("createdAt") Timestamp createdAt);

    /** 按乐观锁更新提供方配置。 */
    int update(
            @Param("tenantId") String tenantId,
            @Param("providerKey") String providerKey,
            @Param("displayName") String displayName,
            @Param("issuerUri") String issuerUri,
            @Param("clientId") String clientId,
            @Param("clientSecret") String clientSecret,
            @Param("scopes") String scopes,
            @Param("enabled") boolean enabled,
            @Param("updatedBy") String updatedBy,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Timestamp updatedAt);

    /** 按标识删除提供方配置。 */
    int deleteById(@Param("tenantId") String tenantId, @Param("id") String id);
}
