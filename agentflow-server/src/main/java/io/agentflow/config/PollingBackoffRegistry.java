package io.agentflow.config;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 后台轮询空转自适应退避与本地事务提交后即时唤醒注册表。
 * 连续空队列时逐步拉长下次扫描间隔以降低空闲连接池占用；一旦有任务入队或处理成功，立即恢复实时响应。
 * @author owlzhangfq@gmail.com
 */
@Component
public class PollingBackoffRegistry {
    private static final long DEFAULT_BASE_DELAY_MS = 1000L;
    private static final long DEFAULT_MAX_BACKOFF_MS = 5000L;
    private static final int MAX_SHIFT = 3;
    private final Clock clock;
    private final long baseDelayMs;
    private final long maxBackoffMs;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /** 默认采用系统时钟、1 秒基准间隔与 5 秒最大退避上限。 */
    public PollingBackoffRegistry() { this(Clock.systemUTC(), DEFAULT_BASE_DELAY_MS, DEFAULT_MAX_BACKOFF_MS); }

    PollingBackoffRegistry(Clock clock, long baseDelayMs, long maxBackoffMs) {
        this.clock = clock;
        this.baseDelayMs = Math.max(100L, baseDelayMs);
        this.maxBackoffMs = Math.max(this.baseDelayMs, maxBackoffMs);
    }

    /** 判断本轮是否允许执行扫描；未到退避结束时刻时直接跳过，避免空转查询数据库。 */
    public boolean shouldPoll(String channel) {
        State state = states.get(channel);
        return state == null || clock.millis() >= state.nextAllowedAtMillis;
    }

    /** 记录本次轮询是否处理了任务；处理到任务立即归零退避，空队列则按指数拉长下次扫描时刻。 */
    public void record(String channel, boolean workDone) {
        if (workDone) {
            wake(channel);
            return;
        }
        State state = states.computeIfAbsent(channel, ignored -> new State());
        int streak = state.emptyStreak.updateAndGet(current -> Math.min(current + 1, MAX_SHIFT));
        long delay = Math.min(maxBackoffMs, baseDelayMs << (streak - 1));
        state.nextAllowedAtMillis = clock.millis() + delay;
    }

    /** 立即唤醒指定轮询通道，下一次调度节拍即可马上扫描数据库。 */
    public void wake(String channel) {
        State state = states.get(channel);
        if (state != null) {
            state.emptyStreak.set(0);
            state.nextAllowedAtMillis = 0L;
        }
    }

    /** 在事务提交后唤醒轮询通道；若当前不在事务中则立即唤醒。 */
    public void wakeAfterCommit(String channel) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { wake(channel); }
            });
        } else {
            wake(channel);
        }
    }

    /** 暴露当前连续空转次数，供测试与诊断核对。 */
    public int emptyStreak(String channel) {
        State state = states.get(channel);
        return state == null ? 0 : state.emptyStreak.get();
    }

    /**
     * 单一轮询通道的轻量退避状态。
     * @author owlzhangfq@gmail.com
     */
    private static final class State {
        private final AtomicInteger emptyStreak = new AtomicInteger();
        private volatile long nextAllowedAtMillis;
    }
}
