package io.agentflow.api.idempotency;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * API 写入边界的幂等事务执行器，领域规则仍由原有用例服务维护。
 * @author owlzhangfq@gmail.com
 */
@Service
public class IdempotencyExecutor {
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotency-Replayed";
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Duration RETENTION = Duration.ofHours(24);
    private final JdbcIdempotencyRepository repository;
    private final CurrentActor currentActor;
    private final JsonUtil json;
    private final TransactionTemplate transaction;

    /** 外层事务负责占位、原业务用例、响应序列化与成功记录的原子提交。 */
    public IdempotencyExecutor(JdbcIdempotencyRepository repository, CurrentActor currentActor, JsonUtil json,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.currentActor = currentActor;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** DTO 校验完成后执行；成功回放不重新要求已完成的任务或流程仍然存在。 */
    public ResponseEntity<String> execute(HttpServletRequest request, HttpStatus successStatus, Supplier<?> operation) {
        Actor actor = currentActor.actor();
        String key = key(request);
        String requestHash = requestHash(request);
        String rolesHash = digest(actor.roles().stream().sorted().map(role -> role.getBytes(StandardCharsets.UTF_8)).toList());
        try {
            return transaction.execute(status -> {
                var existing = repository.find(actor.tenantId(), key);
                if (existing.isPresent()) return replay(existing.get(), actor, rolesHash, requestHash);
                Instant createdAt = Instant.now();
                try {
                    repository.claim(actor.tenantId(), key, actor.userId(), rolesHash, requestHash,
                            createdAt, createdAt.plus(RETENTION));
                } catch (DuplicateKeyException conflict) {
                    // PostgreSQL 唯一键冲突后事务已中止，必须先退出事务，不能在此查询胜方。
                    throw new ClaimConflictException(conflict);
                }
                Object result = operation.get();
                String body = json.write(result);
                repository.complete(actor.tenantId(), key, successStatus.value(), body);
                return response(successStatus.value(), body, false);
            });
        } catch (ClaimConflictException conflict) {
            return transaction.execute(status -> {
                var winner = repository.find(actor.tenantId(), key)
                        .orElseThrow(() -> new DomainException("CONCURRENCY_CONFLICT", "Concurrent idempotency result is unavailable"));
                return replay(winner, actor, rolesHash, requestHash);
            });
        }
    }

    private ResponseEntity<String> replay(JdbcIdempotencyRepository.StoredResponse stored, Actor actor,
                                           String rolesHash, String requestHash) {
        if (!stored.actorId().equals(actor.userId()) || !stored.requestHash().equals(requestHash)) {
            throw new DomainException("IDEMPOTENCY_KEY_REUSED", "Idempotency key belongs to a different request or actor");
        }
        if (!stored.rolesHash().equals(rolesHash)) {
            throw new DomainException("FORBIDDEN", "Authorization context changed since the original request");
        }
        if (!stored.expiresAt().isAfter(Instant.now())) {
            throw new DomainException("IDEMPOTENCY_KEY_EXPIRED", "Idempotency key has expired and cannot be executed again");
        }
        if (stored.status() == null || stored.body() == null) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Idempotency result is not complete");
        }
        return response(stored.status(), stored.body(), true);
    }

    private ResponseEntity<String> response(int status, String body, boolean replayed) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header(REPLAY_HEADER, Boolean.toString(replayed)).body(body);
    }

    private String key(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders(KEY_HEADER));
        if (keys.isEmpty()) throw new DomainException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        if (keys.size() != 1 || !KEY_PATTERN.matcher(keys.get(0)).matches()) {
            throw new DomainException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1 to 128 ASCII letters, digits, dots, underscores, colons or hyphens");
        }
        return keys.get(0);
    }

    private String requestHash(HttpServletRequest request) {
        ContentCachingRequestWrapper wrapped = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if (wrapped == null) throw new IllegalStateException("Authenticated request body caching is unavailable");
        try {
            // 无 @RequestBody 的发布接口也要读取真实 body；DTO 已消费部分由 wrapper 保留，剩余字节不能遗漏。
            wrapped.getInputStream().transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw new DomainException("INVALID_IDEMPOTENCY_REQUEST", "Unable to read the complete request body");
        }
        List<byte[]> parts = new ArrayList<>();
        parts.add(request.getMethod().getBytes(StandardCharsets.UTF_8));
        parts.add(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
        parts.add(request.getQueryString() == null ? null : request.getQueryString().getBytes(StandardCharsets.UTF_8));
        parts.add(wrapped.getContentAsByteArray());
        return digest(parts);
    }

    private String digest(List<byte[]> parts) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                // 长度前缀保留 null、空内容与分段边界，避免拼接歧义。
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(part == null ? -1 : part.length).array());
                if (part != null) hash.update(part);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 仅用于将 claim 唯一冲突带到已经回滚的事务外，不暴露给客户端。
     * @author owlzhangfq@gmail.com
     */
    private static final class ClaimConflictException extends RuntimeException {
        private ClaimConflictException(DuplicateKeyException cause) {
            super(cause);
        }
    }
}
