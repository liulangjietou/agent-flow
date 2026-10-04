package io.agentflow.expense;

import io.agentflow.approval.SubmissionRoundCompleted;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetOccupation;
import io.agentflow.finance.BudgetOperationService;
import io.agentflow.finance.JdbcBudgetOccupationRepository;
import io.agentflow.finance.JdbcBudgetOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 保留期限和外部预算之间的短事务编排，共用报销锁与重新提交串行。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseBudgetRetentionService {
    private final ExpenseReportRepository reports;
    private final ApplicationRepository applications;
    private final SubmissionRoundRepository rounds;
    private final JdbcExpenseBudgetRetentionRepository retentions;
    private final JdbcBudgetOccupationRepository occupations;
    private final JdbcBudgetOperationRepository operations;
    private final BudgetOperationService budgets;

    /** 仅登记既有预算执行器的命令，不在事务内访问外部预算系统。 */
    public ExpenseBudgetRetentionService(ExpenseReportRepository reports, ApplicationRepository applications,
            SubmissionRoundRepository rounds, JdbcExpenseBudgetRetentionRepository retentions,
            JdbcBudgetOccupationRepository occupations, JdbcBudgetOperationRepository operations, BudgetOperationService budgets) {
        this.reports = reports; this.applications = applications; this.rounds = rounds; this.retentions = retentions;
        this.occupations = occupations; this.operations = operations; this.budgets = budgets;
    }

    /** 与轮次结论一起固定策略；后续配置变化、重复事件和草稿编辑不能推迟原期限。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retain(SubmissionRoundCompleted event, ExpenseBudgetRetention.Policy policy) {
        var report = reports.findByApplication(event.tenantId(), event.applicationId()).orElse(null);
        if (report == null) return;
        reports.lock(event.tenantId(), report.id());
        if (retentions.find(event.tenantId(), report.id(), event.roundNo()).isPresent()) return;
        var round = rounds.findByRound(event.tenantId(), event.applicationId(), event.roundNo()).orElseThrow();
        retentions.create(ExpenseBudgetRetention.retain(event.tenantId(), report.id(), event.applicationId(), event.roundNo(),
                round.status(), round.completedAt(), policy));
    }

    /** 未发送释放时复核当前轮次；已经发送时只核对原号，永不因新轮次另发一次释放。 */
    @Transactional
    public void process(JdbcExpenseBudgetRetentionRepository.Candidate candidate, Instant at) {
        var now = at.truncatedTo(ChronoUnit.MICROS);
        reports.lock(candidate.tenantId(), candidate.reportId());
        var current = retentions.find(candidate.tenantId(), candidate.reportId(), candidate.roundNo()).orElseThrow();
        if (current.terminal() || now.isBefore(current.expiresAt())) return;
        ExpenseBudgetRetention next;
        if (current.releaseOperationId() != null) {
            next = current.complete(operations.find(current.tenantId(), current.releaseOperationId()).orElseThrow(), now);
        } else {
            var application = applications.findById(current.tenantId(), current.applicationId()).orElseThrow();
            if (application.roundNo() != current.roundNo() || !application.status().name().equals(current.stoppedStatus().name())) {
                next = current.supersede(now);
            } else {
                var occupation = occupations.find(current.tenantId(), current.reportId()).orElse(null);
                if (occupation != null && occupation.pendingOperationId() != null) next = current.reconcile(now);
                else if (occupation != null && occupation.status() == BudgetOccupation.Status.FROZEN) {
                    next = current.queue(budgets.finalizeOccupation(current.tenantId(), current.reportId(), BudgetCommand.Action.RELEASE, now), now);
                } else next = current.unfrozen(now);
            }
        }
        if (next != current) retentions.update(next);
    }
}
