package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 原放款退回沿用借款资金来源与锁顺序，并额外固定首次实际成功的资金修订。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceDisbursementReturnSources {
    private final AdvanceRepaymentSources sources;
    private final JdbcPaymentOperationRepository payments;
    /** 组合已有原放款校验，不把新还款的当前成功要求用于历史退回。 */
    public AdvanceDisbursementReturnSources(AdvanceRepaymentSources sources, JdbcPaymentOperationRepository payments) { this.sources = sources; this.payments = payments; }
    /** 与原付款、还款和报销资源使用相同申请锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source locked(String tenant, UUID advanceId) { return original(sources.locked(tenant, advanceId)); }
    /** 当前申请或主数据不能替换曾经实际生效的放款。 */
    public Source find(String tenant, UUID advanceId) { return original(sources.find(tenant, advanceId)); }
    private Source original(AdvanceRepaymentSources.Source funding) {
        var command = funding.payment().input().command();
        var original = payments.firstSuccessfulRevision(command.tenantId(), command.id()).orElseThrow(AdvanceDisbursementReturnSources::changed);
        if (!original.input().equals(funding.payment().input()) || !original.observation().paymentReference().equals(funding.advance().paymentReference())) throw changed();
        return new Source(funding, original.version(), new AdvanceDisbursementReturnPort.Request(command, original.observation()));
    }
    private static DomainException changed() { return new DomainException("DISBURSEMENT_RETURN_SOURCE_CHANGED", "Original successful disbursement revision is unavailable or inconsistent"); }
    /**
     * 当前借款、原付款和固定成功修订各自保留，外部读取只接收固定请求。
     * @author owlzhangfq@gmail.com
     */
    public record Source(AdvanceRepaymentSources.Source funding, long paymentVersion, AdvanceDisbursementReturnPort.Request request) { }
}
