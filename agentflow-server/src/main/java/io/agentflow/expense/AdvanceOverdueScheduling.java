package io.agentflow.expense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 有界遍历真实借款来源，失败单笔在下一轮重试，多实例依靠持久证据去重。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.advances.overdue.reminders-enabled", havingValue = "true", matchIfMissing = true)
public class AdvanceOverdueScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(AdvanceOverdueScheduling.class);
    private final JdbcAdvanceOverdueRepository repository;
    private final AdvanceOverdueReminders reminders;
    private JdbcAdvanceOverdueRepository.Candidate after;

    /** 独立调用事务服务，避免调度类内部调用绕过事务代理。 */
    public AdvanceOverdueScheduling(JdbcAdvanceOverdueRepository repository, AdvanceOverdueReminders reminders) { this.repository = repository; this.reminders = reminders; }

    /** 扫描尾部后从头复查尚未逾期或暂时失败的借款，不重复通知已记录项。 */
    @Scheduled(initialDelayString = "${agentflow.advances.overdue.reminder-delay-ms:60000}", fixedDelayString = "${agentflow.advances.overdue.reminder-delay-ms:60000}")
    public void poll() {
        var now = Instant.now();
        try {
            var page = repository.candidates(now, after);
            for (var candidate : page) {
                try { reminders.remind(candidate, now); }
                catch (RuntimeException failure) { LOG.error("Advance overdue reminder failed, errorCode={}, tenant={}, advanceId={}", "ADVANCE_OVERDUE_REMINDER_FAILED", candidate.tenantId(), candidate.id(), failure); }
            }
            after = page.size() == JdbcAdvanceOverdueRepository.BATCH_SIZE ? page.get(page.size() - 1) : null;
        } catch (RuntimeException failure) { LOG.error("Advance overdue scan failed, errorCode={}", "ADVANCE_OVERDUE_SCAN_FAILED", failure); }
    }
}
