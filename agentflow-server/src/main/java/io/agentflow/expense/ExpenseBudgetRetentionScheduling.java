package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 有界遍历到期保留记录；原预算未知或单笔失败不阻塞后续页。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.expenses.budget-retention.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExpenseBudgetRetentionScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseBudgetRetentionScheduling.class);
    private final JdbcExpenseBudgetRetentionRepository repository;
    private final ExpenseBudgetRetentionService service;
    private JdbcExpenseBudgetRetentionRepository.Candidate after;

    /** 调度与单笔事务分别托管，避免类内调用绕过事务代理。 */
    public ExpenseBudgetRetentionScheduling(JdbcExpenseBudgetRetentionRepository repository, ExpenseBudgetRetentionService service) {
        this.repository = repository; this.service = service;
    }

    /** 到达末页后从头核对仍未确认的原命令；重启从头扫描依然安全。 */
    @Scheduled(initialDelayString = "${agentflow.expenses.budget-retention.delay-ms:10000}", fixedDelayString = "${agentflow.expenses.budget-retention.delay-ms:10000}")
    public void poll() {
        var now = Instant.now();
        try {
            var page = repository.candidates(now, after);
            for (var candidate : page) {
                try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-budget-retention", candidate.reportId() + ":" + candidate.roundNo())
                        .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                    try {
                        service.process(candidate, now);
                        LOG.info("Direct scheduler execution completed, errorCode={}, source={}, objectId={}", "NONE", "expense-budget-retention", candidate.reportId() + ":" + candidate.roundNo());
                    }
                    catch (RuntimeException failure) {
                        LOG.error("Budget retention processing failed, errorCode={}, tenant={}, reportId={}, round={}",
                                "BUDGET_RETENTION_PROCESSING_FAILED", candidate.tenantId(), candidate.reportId(), candidate.roundNo());
                    }
                }
            }
            after = page.size() == JdbcExpenseBudgetRetentionRepository.BATCH_SIZE ? page.get(page.size() - 1) : null;
        } catch (RuntimeException failure) { LOG.error("Budget retention scan failed, errorCode={}", "BUDGET_RETENTION_SCAN_FAILED"); }
    }
}
