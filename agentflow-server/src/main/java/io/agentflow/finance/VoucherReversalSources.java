package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.expense.AdvanceRequestRepository;
import io.agentflow.expense.ExpenseReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 冲销只引用本地首次接受的原过账和当前状态，不从新科目映射重造历史命令。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherReversalSources {
    private final JdbcVoucherOperationRepository operations;
    private final ApprovedVoucherSources locks;
    private final ApplicationRepository applications;
    private final AdvanceRequestRepository advances;
    private final ExpenseReportRepository expenses;
    /** 源事实与申请锁由现有凭证仓储提供。 */
    public VoucherReversalSources(JdbcVoucherOperationRepository operations, ApprovedVoucherSources locks,
            ApplicationRepository applications, AdvanceRequestRepository advances, ExpenseReportRepository expenses) {
        this.operations = operations; this.locks = locks; this.applications = applications; this.advances = advances; this.expenses = expenses;
    }
    /** 路由必须同时匹配已授权申请、业务和轮次，知道凭证号不构成访问权。 */
    public Source find(VoucherAccess.Context context, UUID id) {
        var source = find(context.application().tenantId(), id); var command = source.current().input().command(); var binding = command.binding();
        if (!binding.applicationId().equals(context.application().id()) || !binding.businessId().equals(context.application().businessReference().id())
                || binding.roundNo() != context.roundNo() || command.kind() != context.kind() && command.kind() != VoucherCommand.Kind.PAYMENT) throw notFound();
        return source;
    }
    /** 后台只按固定租户和操作读取，原修订不可被争议候选替代。 */
    public Source find(String tenant, UUID id) {
        var current = operations.find(tenant, id).orElseThrow(VoucherReversalSources::notFound);
        var original = operations.firstAcceptedPosting(tenant, id).orElseThrow(VoucherReversalSources::notFound);
        if (!original.input().equals(current.input())) throw new IllegalStateException("Original voucher input changed across revisions");
        return new Source(current, original.version(), new VoucherReversalPort.Request(original.input().command(), original.observation()));
    }
    /** 后台也沿申请、财务聚合的既有顺序加锁，事务外才访问 ERP。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source locked(String tenant, UUID id) {
        var source = find(tenant, id); var command = source.current().input().command(); var binding = command.binding();
        locks.lock(new VoucherPreparation.Source(tenant, JdbcVoucherOperationRepository.businessType(command), binding.businessId(), binding.applicationId(),
                binding.roundNo(), binding.applicationVersion(), binding.businessVersion(), command.employeeId()));
        return find(tenant, id);
    }
    /** 后台首次发送前仍核对财务审阅时的申请和业务版本，历史原件不会因业务变化而被改写。 */
    public void requireVersions(VoucherReversalPreparation.Input input) {
        var command = input.source().command(); var binding = command.binding(); var tenant = command.tenantId();
        var application = applications.findById(tenant, binding.applicationId()).orElseThrow(VoucherReversalSources::changed);
        if (application.businessReference() == null || !application.businessReference().id().equals(binding.businessId())
                || application.version() != input.applicationVersion()) throw changed();
        long version = application.businessReference().type() == BusinessReference.Type.ADVANCE_REQUEST
                ? advances.find(tenant, binding.businessId()).orElseThrow(VoucherReversalSources::changed).version()
                : expenses.find(tenant, binding.businessId()).orElseThrow(VoucherReversalSources::changed).version();
        if (version != input.businessVersion()) throw changed();
    }
    private static DomainException changed() { return new DomainException("VOUCHER_REVERSAL_SOURCE_CHANGED", "Financial source changed after reversal review"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Original accepted voucher for this financial round not found"); }
    /**
     * 首次接受的原件与当前独立裁决状态各有用途，不能相互覆盖。
     * @author owlzhangfq@gmail.com
     */
    public record Source(VoucherOperation current, long originalVersion, VoucherReversalPort.Request request) { }
}
