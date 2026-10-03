package io.agentflow.expense;

import io.agentflow.finance.FinanceCatalog;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 预检及正式提交共同读取当前逾期事实，不把历史 READY 当作永久放行依据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceOverdueChecks {
    private final AdvanceOverdueConfiguration configuration;
    private final JdbcAdvanceOverdueRepository overdue;
    private final EmployeeAdvanceRepository balances;
    /** 跨聚合判断放在应用层，余额和逾期含义仍由借款实体负责。 */
    public AdvanceOverdueChecks(AdvanceOverdueConfiguration configuration, JdbcAdvanceOverdueRepository overdue, EmployeeAdvanceRepository balances) {
        this.configuration = configuration; this.overdue = overdue; this.balances = balances;
    }

    /** 检查全部到期页；冻结和预留不会消除欠款，已真实还清则不再阻断。 */
    public String failure(String tenant, String employee, FinanceCatalog.LegalEntity entity, Instant now) {
        if (configuration.policy(tenant) != AdvanceOverdueConfiguration.Policy.BLOCK) return null;
        var date = LocalDate.ofInstant(now, ZoneId.of(entity.timeZone()));
        JdbcAdvanceOverdueRepository.Candidate after = null;
        while (true) {
            var page = overdue.ownedDueBefore(tenant, employee, entity.id(), date, after);
            var found = balances.findAll(tenant, page.stream().map(JdbcAdvanceOverdueRepository.Candidate::id).toList());
            for (var candidate : page) {
                var advance = found.get(candidate.id());
                if (advance == null || !advance.employeeId().equals(employee) || !advance.legalEntityId().equals(entity.id()) || !advance.dueOn().equals(candidate.dueOn())) {
                    throw new IllegalStateException("Advance overdue index is inconsistent");
                }
                if (advance.overdue(date)) return "ADVANCE_OVERDUE";
            }
            if (page.size() < JdbcAdvanceOverdueRepository.BATCH_SIZE) return null;
            after = page.get(page.size() - 1);
        }
    }
}
