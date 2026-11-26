package io.agentflow.auth.mapper;

import io.agentflow.mybatis.SqlRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 本地用户账号与持久化登录会话 SQL 映射。
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface LocalIdentityMapper {
    /** 按租户与用户名查找本地账号。 */
    List<SqlRow> findByUsername(@Param("tenantId") String tenantId, @Param("username") String username);

    /** 按租户与标识查找本地账号。 */
    List<SqlRow> findById(@Param("tenantId") String tenantId, @Param("id") String id);

    /** 列出本租户全部本地账号（按用户名排序）。 */
    List<SqlRow> listByTenant(@Param("tenantId") String tenantId);

    /** 插入本地用户账号。 */
    int insertUser(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash,
            @Param("rolesJson") String rolesJson,
            @Param("enabled") boolean enabled,
            @Param("createdAt") Timestamp createdAt);

    /** 更新登录失败次数与锁定时间。 */
    int updateLoginFailure(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("failedAttempts") int failedAttempts,
            @Param("lockedUntil") Timestamp lockedUntil,
            @Param("updatedAt") Timestamp updatedAt);

    /** 登录成功后清零失败计数。 */
    int clearLoginFailure(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("updatedAt") Timestamp updatedAt);

    /** 管理员更新启停与角色。 */
    int updateAccountStatus(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("enabled") boolean enabled,
            @Param("rolesJson") String rolesJson,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新口令哈希并清零锁定状态。 */
    int updatePassword(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("passwordHash") String passwordHash,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Timestamp updatedAt);

    /** 更新 TOTP 密钥与开启状态。 */
    int updateMfa(
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("mfaEnabled") boolean mfaEnabled,
            @Param("mfaSecret") String mfaSecret,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedAt") Timestamp updatedAt);

    /** 新增持久化会话记录（仅保存令牌 SHA-256 指纹）。 */
    int insertSession(
            @Param("tokenHash") String tokenHash,
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("rolesJson") String rolesJson,
            @Param("createdAt") Timestamp createdAt,
            @Param("expiresAt") Timestamp expiresAt);

    /** 按令牌哈希查找有效会话。 */
    List<SqlRow> findSession(@Param("tokenHash") String tokenHash);

    /** 撤销单条会话。 */
    int revokeSession(@Param("tokenHash") String tokenHash, @Param("revokedAt") Timestamp revokedAt);

    /** 撤销某用户全部未撤销会话（改密/禁用/重置密码时调用）。 */
    int revokeAllUserSessions(
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("revokedAt") Timestamp revokedAt);
}
