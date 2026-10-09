package io.agentflow.api.idempotency.mapper;

import io.agentflow.mybatis.SqlRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JdbcIdempotencyRepository 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface IdempotencyRepositoryMapper {
    /** 读取 find 所需的持久化事实。 */
    List<SqlRow> find(@Param("tenantId") String tenantId, @Param("key") String key);

    /** 新增 claim 所需的持久化事实。 */
    int claim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("actorId") String actorId,
            @Param("rolesHash") String rolesHash,
            @Param("requestHash") String requestHash,
            @Param("createdAt") OffsetDateTime createdAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    /** 更新 complete 所需的持久化事实。 */
    int complete(
            @Param("responseStatus") Integer responseStatus,
            @Param("body") String body,
            @Param("tenantId") String tenantId,
            @Param("key") String key);
}
