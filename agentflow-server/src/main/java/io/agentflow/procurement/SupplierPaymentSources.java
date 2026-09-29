package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出纳准备与银行发送共用原申请锁和当前资金资格，恢复查询只取得原来源锁。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SupplierPaymentSources {
    private final ApprovedSupplierPaymentSources approved;
    private final JdbcSupplierPaymentAuthorizationRepository authorizations;
    private final JdbcSupplierPayableHoldRepository holds;
    private final PaymentPersonnel personnel;

    /** 当前批准、任职及实际预留分别来自其所属聚合。 */
    public SupplierPaymentSources(ApprovedSupplierPaymentSources approved, JdbcSupplierPaymentAuthorizationRepository authorizations,
            JdbcSupplierPayableHoldRepository holds, PaymentPersonnel personnel) {
        this.approved = approved; this.authorizations = authorizations; this.holds = holds; this.personnel = personnel;
    }

    /** 沿用申请优先的锁顺序，已经失效的来源仍允许查回原交易。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPaymentAuthorization lock(String tenant, UUID id) {
        var original = authorization(tenant, id); approved.lock(original); return authorization(tenant, id);
    }

    /** 后台只读读取真实原授权，不在网络调用期间持有来源锁。 */
    public SupplierPaymentAuthorization authorization(String tenant, UUID id) {
        return authorizations.find(tenant, id).orElseThrow(SupplierPaymentSources::changed);
    }

    /** 新命令和新发送必须仍是实际有效授权，并满足法人任职及三方分离。 */
    public void requireCurrent(SupplierPaymentAuthorization original, String cashier, Instant now) {
        var source = original.source().reservation().source();
        if (!authorizations.activeForRequest(source.tenantId(), source.requestId()).filter(original::equals).isPresent()) throw changed();
        approved.requireCurrent(original); original.requireCashier(cashier, now);
        personnel.requireEligible(source.tenantId(), original.authorizedBy(), source.round().content().legalEntityId());
        personnel.requireEligible(source.tenantId(), cashier, source.round().content().legalEntityId());
    }

    /** 本地必须已保存无争议的原 HELD；只读网关结果不能自行补造此前预留成功。 */
    public SupplierPayableHoldOperation held(SupplierPaymentAuthorization original) {
        var hold = holds.find(original.source().reservation().source().tenantId(), original.id()).orElseThrow(SupplierPaymentSources::evidenceChanged);
        if (hold.status() != SupplierPayableHoldOperation.Status.HELD || !hold.command().authorization().equals(original)) throw evidenceChanged();
        return hold;
    }
    private static DomainException changed() { return new DomainException("SUPPLIER_PAYMENT_SOURCE_CHANGED", "Original supplier authorization or actual approved source changed"); }
    private static DomainException evidenceChanged() { return new DomainException("SUPPLIER_PAYMENT_EVIDENCE_CHANGED", "Original supplier payable hold is not confirmed"); }
}
