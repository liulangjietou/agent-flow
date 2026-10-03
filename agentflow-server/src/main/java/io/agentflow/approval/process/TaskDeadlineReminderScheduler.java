package io.agentflow.approval.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 每轮有界扫描到期任务；单任务失败保留重试资格，不影响其余任务。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.sla.reminders-enabled", havingValue = "true", matchIfMissing = true)
public class TaskDeadlineReminderScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TaskDeadlineReminderScheduler.class);
    private final FlowableTaskDeadlineReminders reminders;
    private FlowableTaskDeadlineReminders.Candidate after;

    /** 调度与独立事务服务分开，确保每个任务的锁与提交边界生效。 */
    public TaskDeadlineReminderScheduler(FlowableTaskDeadlineReminders reminders) { this.reminders = reminders; }

    /** 到达队列尾部后重新扫描；无接收人及暂时失败的任务在下一轮重新复核。 */
    @Scheduled(initialDelayString = "${agentflow.sla.reminder-delay-ms:30000}",
            fixedDelayString = "${agentflow.sla.reminder-delay-ms:30000}")
    public void deliver() {
        Instant now = Instant.now();
        try {
            var candidates = reminders.candidates(now, after);
            for (var candidate : candidates) {
                try {
                    reminders.remind(candidate.taskId(), now);
                } catch (RuntimeException exception) {
                    LOG.error("Task deadline reminder failed, errorCode={}, taskId={}", "SLA_REMINDER_FAILED", candidate.taskId(), exception);
                }
            }
            after = candidates.size() == FlowableTaskDeadlineReminders.BATCH_SIZE ? candidates.get(candidates.size() - 1) : null;
        } catch (RuntimeException exception) {
            LOG.error("Task deadline scan failed, errorCode={}", "SLA_SCAN_FAILED", exception);
        }
    }
}
