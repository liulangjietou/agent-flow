package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回款来源固定首次成功银行修订，核销前后均可沿原目标核对真实入款。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentReturnSources {
    private final SupplierPaymentSources payments;
    private final JdbcSupplierPaymentOperationRepository operations;
    private final SupplierSettlementSources settlement;

    /** 原申请锁与当前独立财务资格复用已有资金编排。 */
    public SupplierPaymentReturnSources(SupplierPaymentSources payments, JdbcSupplierPaymentOperationRepository operations, SupplierSettlementSources settlement) {
        this.payments = payments; this.operations = operations; this.settlement = settlement;
    }

    /** 恢复查询不要求原授权未到期，也不要求原应付尚未核销。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source locked(String tenant, UUID paymentId) { payments.lock(tenant, paymentId); return find(tenant, paymentId).orElseThrow(SupplierPaymentReturnSources::changed); }

    /** 首次成功和当前银行分开读取，后续争议不能替换原资金身份。 */
    public Optional<Source> find(String tenant, UUID paymentId) {
        var current = operations.find(tenant, paymentId).orElse(null); if (current == null) return Optional.empty();
        var original = operations.firstSuccessfulRevision(tenant, paymentId).orElse(null); if (original == null) return Optional.empty();
        if (!current.command().equals(original.command())) throw changed();
        return Optional.of(new Source(current, original.version(), new SupplierPaymentReturnPort.Request(original.command(), original.observation())));
    }

    /** 排队和领取都核对原首次成功，客户端不能另选修订或交易。 */
    public void requireCheck(SupplierPaymentReturnCheck check, Source source) {
        if (check.input().paymentVersion() != source.paymentVersion() || !check.input().request().equals(source.request())) throw changed();
    }

    /** 已离职财务不能消费新原件，当前财务仍不得是原申请人或原出纳。 */
    public void requireFinance(Source source, String actor) { settlement.requireFinance(source.request().command(), actor); }

    private static DomainException changed() { return new DomainException("SUPPLIER_PAYMENT_RETURN_SOURCE_CHANGED", "Supplier return requires the original successful bank transaction"); }

    /**
     * 仅用于内部编排，完整资金命令不直接返回浏览器。
     * @author owlzhangfq@gmail.com
     */
    public record Source(SupplierPaymentOperation payment, long paymentVersion, SupplierPaymentReturnPort.Request request) { }
}
