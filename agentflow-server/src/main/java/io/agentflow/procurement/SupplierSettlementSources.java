package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销从已到账事实出发，只在新增结算写入时复核批准、占用和当前结算财务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierSettlementSources {
    private final SupplierPaymentSources paymentSources;
    private final ApprovedSupplierPaymentSources approved;
    private final JdbcSupplierPaymentOperationRepository payments;
    private final PaymentPersonnel personnel;
    private final SupplierPayableReturnGuard returns;

    /** 已付款后的核销不继承银行发送的授权到期门槛，原资金身份与申请锁仍保持。 */
    public SupplierSettlementSources(SupplierPaymentSources paymentSources, ApprovedSupplierPaymentSources approved,
            JdbcSupplierPaymentOperationRepository payments, PaymentPersonnel personnel, SupplierPayableReturnGuard returns) {
        this.paymentSources = paymentSources; this.approved = approved; this.payments = payments; this.personnel = personnel; this.returns = returns;
    }

    /** 使用与银行、采购相同的申请优先锁，失效来源仍允许查询原结算。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentOperation lock(String tenant, UUID paymentId) {
        paymentSources.lock(tenant, paymentId); return payment(tenant, paymentId);
    }

    /** 读取当前银行事实，不能用准备里的旧成功覆盖当前查询或争议。 */
    public SupplierPaymentOperation payment(String tenant, UUID paymentId) {
        return payments.find(tenant, paymentId).orElseThrow(SupplierSettlementSources::changed);
    }

    /** 银行实际回款冻结本地补全，原 ERP 成功继续保存并等待独立调整。 */
    public boolean returnReviewRequired(SupplierPaymentCommand command) {
        return returns.blocked(command.tenantId(), command.holdCommand().authorization().source().reservation().source().round().content());
    }

    /** 新准备和新发送依赖当前批准、原占用及结算人员，原出纳离职不撤销已发生付款。 */
    public void requireCurrent(SupplierPaymentCommand original, String finance, Instant now) {
        var bank = payment(original.tenantId(), original.id());
        if (!bank.settleable() || !bank.command().equals(original) || now.isBefore(bank.updatedAt())) throw changed();
        approved.requireCurrent(original.holdCommand().authorization()); requireFinance(original, finance);
        paymentSources.held(original.holdCommand().authorization());
    }

    /** 安全结束由当前独立财务办理；不要求旧财务或出纳继续任职。 */
    public void requireFinance(SupplierPaymentCommand original, String finance) {
        var source = original.holdCommand().authorization().source().reservation().source();
        if (finance.equals(original.cashier()) || finance.equals(source.employeeId())) throw changed();
        personnel.requireEligible(original.tenantId(), finance, source.round().content().legalEntityId());
    }

    /** 网络读取结束后再次与当前本地银行和预留对照，迟到观察不能回退已知事实。 */
    public void requireEvidence(SupplierPayableSettlementCommand command, SupplierPayableSettlementEvidence evidence, Instant now) {
        var bank = payment(command.tenantId(), command.payment().id());
        var hold = paymentSources.held(command.payment().holdCommand().authorization());
        if (!command.matchesCurrentPayment(bank, evidence.paid(), now)
                || !command.payment().matchesCurrentHold(hold, evidence.hold(), now)) throw evidenceChanged();
    }
    private static DomainException changed() { return new DomainException("SUPPLIER_SETTLEMENT_SOURCE_CHANGED", "Actual approved payable, paid bank or settlement finance eligibility changed"); }
    private static DomainException evidenceChanged() { return new DomainException("SUPPLIER_SETTLEMENT_EVIDENCE_CHANGED", "Fresh evidence no longer matches the current original bank and payable hold"); }
}
