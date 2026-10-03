package io.agentflow.approval.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 调度与推进事务分离；失败只有在原推进完全回滚后才记录，不吞掉其他待处理节点。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.timers.enabled", havingValue = "true", matchIfMissing = true)
public class TimerWaitScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TimerWaitScheduler.class);
    private final TimerWaitService waits;
    private TimerWaitService.Candidate after;

    /** 每个候选通过独立 Spring 事务调用，不持有跨批次事务。 */
    public TimerWaitScheduler(TimerWaitService waits) { this.waits = waits; }

    /** 服务重启后读取原生持久任务，不根据新定义重算到期时间。 */
    @Scheduled(initialDelayString = "${agentflow.timers.delay-ms:5000}", fixedDelayString = "${agentflow.timers.delay-ms:5000}")
    public void dispatch() {
        Instant now = Instant.now();
        try {
            var candidates = waits.candidates(now, after);
            for (var candidate : candidates) {
                try { waits.advance(candidate.jobId(), now); }
                catch (RuntimeException failure) {
                    LOG.error("Timer wait failed, errorCode={}, jobId={}", "TIMER_EXECUTION_FAILED", candidate.jobId(), failure);
                    try { waits.failed(candidate.jobId(), failure); }
                    catch (RuntimeException recordingFailure) {
                        LOG.error("Timer wait failure recording failed, errorCode={}, jobId={}", "TIMER_FAILURE_RECORDING_FAILED", candidate.jobId(), recordingFailure);
                    }
                }
            }
            after = candidates.size() == TimerWaitService.BATCH_SIZE ? candidates.get(candidates.size() - 1) : null;
        } catch (RuntimeException failure) {
            LOG.error("Timer wait scan failed, errorCode={}", "TIMER_SCAN_FAILED", failure);
        }
    }
}
