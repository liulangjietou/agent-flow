package io.agentflow.finance;

import io.agentflow.approval.model.Application;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同一执行队列支持批准挂账与真实付款两种依据，各自规则仍由对应来源服务负责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class VoucherSources {
    private final ApprovedVoucherSources approved;
    private final PaymentVoucherSources paid;
    private final JdbcVoucherPreparationRepository preparations;
    /** 仅组合来源，不复制两类业务守卫。 */
    public VoucherSources(ApprovedVoucherSources approved, PaymentVoucherSources paid, JdbcVoucherPreparationRepository preparations) {
        this.approved = approved; this.paid = paid; this.preparations = preparations;
    }
    /** 最终批准创建原挂账来源。 */
    public VoucherPreparation.Source reference(Application application) { return approved.reference(application); }
    /** 实际到账创建固定回单修订来源。 */
    public VoucherPreparation.Source reference(PaymentOperation payment) { return paid.reference(payment); }
    /** 付款命令必须来自已持久准备，任意调用方构造的自证成功不能成为会计依据。 */
    public VoucherPreparation.Source reference(VoucherCommand command) {
        if (command.kind() != VoucherCommand.Kind.PAYMENT) return approved.reference(command);
        var preparation = preparations.find(command.tenantId(), command.id()).orElseThrow(VoucherSources::changed);
        var source = preparation.input().source(); var binding = command.binding();
        if (source.kind() != VoucherCommand.Kind.PAYMENT || !source.paymentOperationId().equals(command.payment().command().id())
                || !source.applicationId().equals(binding.applicationId()) || !source.businessId().equals(binding.businessId())
                || source.roundNo() != binding.roundNo() || source.applicationVersion() != binding.applicationVersion()
                || source.businessVersion() != binding.businessVersion() || !source.employeeId().equals(command.employeeId())) throw changed();
        return source;
    }
    /** 两类来源使用相同的申请优先、业务聚合其次锁顺序。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(VoucherPreparation.Source source) { approved.lock(source); }
    /** 付款不重新要求审批处于已批准，挂账继续使用原批准守卫。 */
    public VoucherSource.Plan derive(VoucherPreparation.Source source) {
        return source.kind() == VoucherCommand.Kind.PAYMENT ? paid.derive(source) : approved.derive(source);
    }
    private static DomainException changed() { return new DomainException("PAYMENT_VOUCHER_SOURCE_CHANGED", "Payment accounting command requires its original persisted preparation"); }
}
