package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import io.agentflow.finance.VoucherAccess;
import org.springframework.stereotype.Service;
import java.util.UUID;

/**
 * 结算沿用当轮完整财务字段权限，人工重试还须当前法人财务任职及职责分离。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementAccess {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final VoucherAccess access;
    private final PaymentPersonnel personnel;
    /** 不通过 ADMIN 或出纳角色绕过原业务敏感字段。 */
    public ExpenseSettlementAccess(CurrentActor actors, ExpenseReportRepository reports, VoucherAccess access, PaymentPersonnel personnel) {
        this.actors = actors; this.reports = reports; this.access = access; this.personnel = personnel;
    }
    /** 原报销身份只用于定位，响应前必须通过实际申请及财务轮次权限。 */
    public VoucherAccess.Context read(UUID reportId, Integer round) {
        var report = reports.find(actors.actor().tenantId(), reportId).orElseThrow(ExpenseSettlementAccess::notFound);
        return access.read(report.applicationId(), round);
    }
    /** 同法人有效任职不能被历史财务参与记录代替。 */
    public VoucherAccess.Context requireFinance(UUID reportId, int round) {
        var context = read(reportId, round);
        if (!canManage(context, reportId)) throw new DomainException("FORBIDDEN", "Current finance appointment and original financial access are required");
        return context;
    }
    /** 页面提示与写入入口复用同一岗位判断，锁后仍须重新授权。 */
    public boolean canManage(VoucherAccess.Context context, UUID reportId) {
        if (!context.finance()) return false;
        var report = reports.find(actors.actor().tenantId(), reportId).orElseThrow(ExpenseSettlementAccess::notFound);
        return personnel.eligible(actors.actor().tenantId(), actors.actor().userId(), report.content().legalEntityId());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense settlement is unavailable in the current scope"); }
}
