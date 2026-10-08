package io.agentflow.api.idempotency;


import io.agentflow.api.idempotency.mapper.IdempotencyRepositoryMapper;
import io.agentflow.common.DomainException;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 与业务及 Flowable 共用数据源的请求幂等记录，只有成功响应可以与业务一并提交。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcIdempotencyRepository {
    private final IdempotencyRepositoryMapper sqlMapper;

    /** 注入当前业务事务的数据源。 */
    public JdbcIdempotencyRepository(IdempotencyRepositoryMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
    }

    /** 读取租户内的精确键，不在其他租户或请求范围查找。 */
    public Optional<StoredResponse> find(String tenantId, String key) {
        return SqlRows.map(
                        sqlMapper.find(tenantId, key),
                        row ->
                                new StoredResponse(
                                        row.getString("actor_id"),
                                        row.getString("roles_hash"),
                                        row.getString("request_hash"),
                                        row.getObject("response_status", Integer.class),
                                        row.getString("response_body"),
                                        row.getObject("expires_at", OffsetDateTime.class)
                                                .toInstant()))
                .stream()
                .findFirst();
    }

    /** 唯一插入取得本次执行资格，冲突由事务边界外处理。 */
    public void claim(
            String tenantId,
            String key,
            String actorId,
            String rolesHash,
            String requestHash,
            Instant createdAt,
            Instant expiresAt) {
        sqlMapper.claim(
                tenantId,
                key,
                actorId,
                rolesHash,
                requestHash,
                createdAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC));
    }

    /** 只完成当前事务的未完成记录；保存失败必须回滚业务副作用。 */
    public void complete(String tenantId, String key, int responseStatus, String body) {
        int updated = sqlMapper.complete(responseStatus, body, tenantId, key);
        if (updated != 1)
            throw new DomainException(
                    "CONCURRENCY_CONFLICT", "Idempotency response could not be completed");
    }

    /**
     * 已存储响应及回放所需的身份、权限和请求摘要。
     *
     * @author owlzhangfq@gmail.com
     */
    public record StoredResponse(
            String actorId,
            String rolesHash,
            String requestHash,
            Integer status,
            String body,
            Instant expiresAt) {}
}
