package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 新准备和新发送共用当前独立财务及原账务核验，已发送结果查询不经过新增授权门槛。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierAdjustmentSources {
    private final JdbcSupplierAdjustmentSources facts;
    private final SupplierSettlementSources finance;
    private final SupplierPaymentSources payments;

    /** 资格与跨系统事实由应用层组合，资金和账本转换继续由领域对象负责。 */
    public SupplierAdjustmentSources(JdbcSupplierAdjustmentSources facts, SupplierSettlementSources finance, SupplierPaymentSources payments) {
        this.facts = facts; this.finance = finance; this.payments = payments;
    }

    /** 仍在任职的独立财务才可发出新调整；原申请人、出纳及已离职人员不能代办。 */
    public void requireCurrent(SupplierPayableAdjustmentSource source, String actor) {
        finance.requireFinance(source.returns().request().command(), actor); facts.requireCurrent(source);
    }

    /** 网络复查不能回退本地银行、原预留、原核销或前次调整已经知道的事实。 */
    public void requireEvidence(SupplierPayableAdjustmentSource source, SupplierPayableAdjustmentEvidence evidence, Instant now) {
        facts.requireEvidence(source, evidence);
        var payment = source.returns().request().command();
        if (source.recognizesOriginalPayment() && !payment.matchesCurrentHold(payments.held(payment.holdCommand().authorization()), evidence.hold(), now)) {
            throw new DomainException("SUPPLIER_ADJUSTMENT_SOURCE_CHANGED", "Current original supplier hold changed before adjustment");
        }
    }
}
