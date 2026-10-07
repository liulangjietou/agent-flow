package io.agentflow.approval.process;

import io.agentflow.observability.DiagnosticContext;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 升级与普通到期提醒分别推进有界游标，空名单或失败任务不会阻塞下一页。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.sla.reminders-enabled", havingValue = "true", matchIfMissing = true)
public class TaskEscalationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TaskEscalationScheduler.class);
    private final FlowableTaskEscalations escalations;
    private FlowableTaskEscalations.Candidate after;

    /** 每张任务由独立事务处理，不在调度器持有业务锁。 */
    public TaskEscalationScheduler(FlowableTaskEscalations escalations) { this.escalations = escalations; }

    /** 一轮失败保留原游标，扫到尾部后复核此前无人可收件的任务。 */
    @Scheduled(initialDelayString = "${agentflow.sla.reminder-delay-ms:30000}", fixedDelayString = "${agentflow.sla.reminder-delay-ms:30000}")
    public void deliver() {
        Instant now = Instant.now();
        try {
            var candidates = escalations.candidates(now, after);
            for (var candidate : candidates) {
                try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "task-escalation", candidate.taskId())
                        .withBusiness(candidate.businessNo(), candidate.processInstanceId(), candidate.taskId()).open()) {
                    try {
                        if (escalations.escalate(candidate.taskId(), now)) {
                            LOG.info("Direct scheduler execution completed, errorCode={}, source={}, objectId={}", "NONE", "task-escalation", candidate.taskId());
                        }
                    }
                    catch (RuntimeException failure) {
                        LOG.error("Task escalation failed, errorCode={}, taskId={}", "SLA_ESCALATION_FAILED", candidate.taskId());
                    }
                }
            }
            after = candidates.size() == FlowableTaskEscalations.BATCH_SIZE ? candidates.get(candidates.size() - 1) : null;
        } catch (RuntimeException failure) { LOG.error("Task escalation scan failed, errorCode={}", "SLA_ESCALATION_SCAN_FAILED"); }
    }
}
