package io.agentflow.procurement;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商资金编排从实际审批、采购和本地占用取得批准依据，外部请求中的声明不能代替来源。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApprovedSupplierPaymentSources {
    private final ApplicationRepository applications;
    private final ProcurementPaymentRepository requests;
    private final JdbcProcurementPayableReservationRepository reservations;
    private final SupplierPayableReturnGuard returns;

    /** 跨聚合规则放在应用层，领域只处理已取得的不可变事实。 */
    public ApprovedSupplierPaymentSources(ApplicationRepository applications, ProcurementPaymentRepository requests, JdbcProcurementPayableReservationRepository reservations, SupplierPayableReturnGuard returns) {
        this.applications = applications; this.requests = requests; this.reservations = reservations; this.returns = returns;
    }

    /** 沿用审批申请优先的锁顺序；查询恢复同样按原申请定位锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(SupplierPaymentAuthorization authorization) {
        lock(authorization.source());
    }

    /** 授权前的应付读取同样使用原批准申请的锁，尚未产生财务决定时无需构造虚假授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ApprovedProcurementPayment approved) {
        var source = approved.reservation().source(); requests.lock(source.tenantId(), source.requestId());
        returns.lock(source.tenantId(), source.round().content());
    }

    /** 新授权及发送读取真实最终批准版本、冻结内容和仍保留的本地应付占用。 */
    public ApprovedProcurementPayment derive(String tenant, UUID requestId) {
        var request = requests.find(tenant, requestId).orElseThrow(ApprovedSupplierPaymentSources::changed);
        var application = applications.findById(tenant, request.applicationId()).orElseThrow(ApprovedSupplierPaymentSources::changed);
        if (request.approval() == null || application.status() != ApplicationStatus.APPROVED || application.businessReference() == null
                || application.businessReference().type() != BusinessReference.Type.PROCUREMENT_PAYMENT || !application.businessReference().id().equals(request.id())
                || !application.createdBy().equals(request.employeeId()) || application.roundNo() != request.approval().roundNo()
                || application.version() != request.approval().applicationVersion()
                || !application.payload().equals(ProcurementPaymentFormContract.submittedPayload(request.currentRound()))) throw changed();
        returns.requireClear(tenant, request.currentRound().content());
        return ApprovedProcurementPayment.from(request, reservations.active(tenant, requestId).orElseThrow(ApprovedSupplierPaymentSources::changed));
    }

    /** 只有新外部副作用才依赖当前批准，已发送命令的查询不因来源变化而停止。 */
    public void requireCurrent(SupplierPaymentAuthorization authorization) {
        var original = authorization.source().reservation().source();
        if (!authorization.source().equals(derive(original.tenantId(), original.requestId()))) throw changed();
    }

    private static DomainException changed() { return new DomainException("PROCUREMENT_PAYMENT_SOURCE_CHANGED", "Actual approved procurement source or held payable changed"); }
}
