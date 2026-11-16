package io.agentflow.api.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 幂等回执保留期结束后清除响应正文，避免业务数据在请求表中无限期留存与增长。
 * 键与请求摘要保留为墓碑：过期键再次提交仍返回 IDEMPOTENCY_KEY_EXPIRED，不会重新执行业务副作用。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.idempotency.purge-enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyRetention {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyRetention.class);
    /** 每批短事务清理的上限，避免一次长事务锁住大量行。 */
    static final int BATCH_SIZE = 500;
    /** 单轮最多处理的批次数，剩余部分留给下一轮。 */
    private static final int MAX_BATCHES = 200;
    private final JdbcIdempotencyRepository repository;
    private final TransactionTemplate transaction;

    /** 每批独立提交，失败只影响当前批次。 */
    public IdempotencyRetention(JdbcIdempotencyRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 定时清理；默认每小时一次。 */
    @Scheduled(initialDelayString = "${agentflow.idempotency.purge-delay-ms:3600000}",
            fixedDelayString = "${agentflow.idempotency.purge-delay-ms:3600000}")
    public void poll() {
        int purged = purgeExpired(Instant.now());
        if (purged > 0) LOG.info("Expired idempotency responses purged, errorCode={}, purged={}", "NONE", purged);
    }

    /** 清理截至 now 已过期的回执正文，返回本轮清理总数。 */
    public int purgeExpired(Instant now) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Integer purged = transaction.execute(status -> repository.purgeExpiredResponses(now, BATCH_SIZE));
            int count = purged == null ? 0 : purged;
            total += count;
            if (count < BATCH_SIZE) break;
        }
        return total;
    }
}
