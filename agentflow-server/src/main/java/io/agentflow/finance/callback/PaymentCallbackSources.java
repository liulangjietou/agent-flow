package io.agentflow.finance.callback;

import io.agentflow.common.DomainException;
import io.agentflow.finance.ApprovedPaymentSources;
import io.agentflow.finance.JdbcPaymentAuthorizationRepository;
import io.agentflow.finance.JdbcPaymentOperationRepository;
import io.agentflow.finance.PaymentOperation;
import io.agentflow.finance.PaymentOperationService;
import io.agentflow.procurement.JdbcSupplierPaymentOperationRepository;
import io.agentflow.procurement.SupplierPaymentOperation;
import io.agentflow.procurement.SupplierPaymentService;
import io.agentflow.procurement.SupplierPaymentSources;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回调跨两种原付款来源编排；保持原申请优先的锁顺序，查询结果仍由各付款领域处理。
 * @author owlzhangfq@gmail.com
 */
@Component
public class PaymentCallbackSources {
    private final ApprovedPaymentSources employeeSources;
    private final JdbcPaymentAuthorizationRepository authorizations;
    private final JdbcPaymentOperationRepository employees;
    private final PaymentOperationService employeeService;
    private final SupplierPaymentSources supplierSources;
    private final JdbcSupplierPaymentOperationRepository suppliers;
    private final SupplierPaymentService supplierService;

    /** 两种真实付款共享回调接收，业务状态和命令存储继续归属原服务。 */
    public PaymentCallbackSources(ApprovedPaymentSources employeeSources, JdbcPaymentAuthorizationRepository authorizations,
            JdbcPaymentOperationRepository employees, PaymentOperationService employeeService, SupplierPaymentSources supplierSources,
            JdbcSupplierPaymentOperationRepository suppliers, SupplierPaymentService supplierService) {
        this.employeeSources = employeeSources; this.authorizations = authorizations; this.employees = employees;
        this.employeeService = employeeService; this.supplierSources = supplierSources; this.suppliers = suppliers; this.supplierService = supplierService;
    }

    /** 先锁定原业务来源再锁回调，和原付款工作器保持一致。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Source lock(PaymentCallbackVerifier.Signal signal) {
        String tenant = signal.tenantId(); var id = signal.authorizationId();
        if (signal.kind() == PaymentCallbackVerifier.Kind.EMPLOYEE) {
            var authorization = authorizations.find(tenant, id).orElseThrow(PaymentCallbackSources::missing);
            employeeSources.lock(authorization);
            var value = employees.find(tenant, id).orElseThrow(PaymentCallbackSources::missing);
            return new Source(value.input().command().digest(), value.input().targetDigest(), value.version(), value.dispatches(), value.highestRevision(),
                    value.status() == PaymentOperation.Status.SENDING || value.status() == PaymentOperation.Status.QUERYING);
        }
        if (suppliers.find(tenant, id).isEmpty()) throw missing();
        supplierSources.lock(tenant, id);
        var value = suppliers.find(tenant, id).orElseThrow(PaymentCallbackSources::missing);
        return new Source(value.command().digest(), value.command().targetDigest(), value.version(), value.dispatches(), value.highestRevision(),
                value.status() == SupplierPaymentOperation.Status.SENDING || value.status() == SupplierPaymentOperation.Status.QUERYING);
    }

    /** 已有来源锁和匹配命令时，只登记查询，不在回调事务里执行任何网络调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long query(PaymentCallbackVerifier.Signal signal, long version, Instant now) {
        return signal.kind() == PaymentCallbackVerifier.Kind.EMPLOYEE
                ? employeeService.callbackQuery(signal.tenantId(), signal.authorizationId(), version, now).version()
                : supplierService.callbackQuery(signal.tenantId(), signal.authorizationId(), version, now).version();
    }
    private static DomainException missing() { return new DomainException("NOT_FOUND", "Original payment operation was not found"); }

    /**
     * 最小状态只用于排队，不向运维列表暴露原金额、账户或申请内容。
     * @author owlzhangfq@gmail.com
     */
    public record Source(String commandDigest, String targetDigest, long version, int dispatches, long highestRevision, boolean busy) { }
}
