package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.VoucherAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调整沿用原轮次完整财务字段与当前任职，并排除原申请人及原付款出纳。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentAccess {
    private final CurrentActor actors;
    private final ExpenseSettlementAccess access;
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcPaymentOperationRepository payments;
    /** 复用已经验证的字段权限，补充原出纳职责分离及统一锁顺序。 */
    public ExpenseResourceAdjustmentAccess(CurrentActor actors, ExpenseSettlementAccess access, ExpenseReportRepository reports,
            JdbcExpenseSettlementRepository settlements, JdbcPaymentOperationRepository payments) {
        this.actors = actors; this.access = access; this.reports = reports; this.settlements = settlements; this.payments = payments;
    }
    /** 幂等回放前也执行，管理员没有原字段权限时不能凭操作号读取或办理。 */
    public VoucherAccess.Context requireFinance(UUID report, int round) {
        var context = access.requireFinance(report, round); var settlement = settlements.find(actors.actor().tenantId(), report).orElse(null);
        if (settlement != null && settlement.input().payment() != null) {
            var payment = payments.find(actors.actor().tenantId(), settlement.input().payment().operationId()).orElseThrow(ExpenseResourceAdjustmentAccess::forbidden);
            if (actors.actor().userId().equals(payment.input().command().authorization().executedBy())) throw forbidden();
        }
        return context;
    }
    /** 先鉴权再锁原申请和报销，等待锁后重新核对当前身份。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherAccess.Context locked(UUID report, int round) {
        requireFinance(report, round); reports.lock(actors.actor().tenantId(), report); return requireFinance(report, round);
    }
    /** 所有人工动作核对实际申请与财务版本，不能把旧页面用于新的轮次。 */
    public void requireVersions(VoucherAccess.Context context, int round, long application, long business) {
        if (context.roundNo() != round || context.application().roundNo() != round || context.application().version() != application || context.businessVersion() != business) {
            throw new DomainException("CONCURRENCY_CONFLICT", "Displayed expense round or financial version changed");
        }
    }
    private static DomainException forbidden() { return new DomainException("FORBIDDEN", "Independent current finance and access to original financial fields are required"); }
}
