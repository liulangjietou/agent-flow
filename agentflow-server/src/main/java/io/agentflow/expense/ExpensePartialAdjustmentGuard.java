package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部分调整和旧整单冲销共用原申请、报销锁；已完成部分调整永久保留原账保护。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExpensePartialAdjustmentGuard {
    private final JdbcTemplate jdbc;
    private final ExpenseReportRepository reports;
    /** 沿既有申请后报销的锁顺序协调两类办理，不另建互不相通的锁。 */
    public ExpensePartialAdjustmentGuard(JdbcTemplate jdbc, ExpenseReportRepository reports) { this.jdbc = jdbc; this.reports = reports; }

    /** 原挂账不能再全额冲销，原资源也不能再按原全额释放；安全结束且无效果的意图除外。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWholeAllowed(String tenant, UUID report) {
        reports.lock(tenant, report);
        if (!jdbc.queryForList("SELECT id FROM expense_partial_adjustment WHERE tenant_id=? AND report_id=? AND retired_at IS NULL LIMIT 1", String.class, tenant, report.toString()).isEmpty()) {
            throw new DomainException("EXPENSE_PARTIAL_ADJUSTMENT_PENDING", "Partial adjustment protects the original accrual and full resource cancellation");
        }
    }

    /** 新部分意图在同一锁内检查旧整单办理，不能与其并发占用同一报销。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePartialAllowed(String tenant, UUID report) {
        reports.lock(tenant, report);
        if (!jdbc.queryForList("SELECT id FROM expense_resource_adjustment WHERE tenant_id=? AND active_report_id=? LIMIT 1", String.class, tenant, report.toString()).isEmpty()) {
            throw new DomainException("EXPENSE_ADJUSTMENT_PENDING", "An existing full resource adjustment protects this expense");
        }
    }
}
