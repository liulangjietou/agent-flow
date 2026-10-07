package io.agentflow.finance;

import io.agentflow.observability.DiagnosticContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 出纳确认后的只读账户复查，不直接调用资金写接口。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentExecutionRequestWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentExecutionRequestWorker.class);
    private static final String TRACE_SOURCE = "payment-execution-request";
    private final JdbcPaymentExecutionRequestRepository requests;
    private final PaymentExecutionRequestService execution;
    private final PaymentAccountsPort accounts;
    /** 领取和结果处理通过独立事务代理，账户等待不占用幂等或申请事务。 */
    public PaymentExecutionRequestWorker(JdbcPaymentExecutionRequestRepository requests, PaymentExecutionRequestService execution, PaymentAccountsPort accounts) {
        this.requests = requests; this.execution = execution; this.accounts = accounts;
    }
    /** 每批最多十笔，调用真实原目标的两类只读接口后再原子登记付款命令。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Payment request worker must execute outside a database transaction");
        for (var candidate : requests.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var work = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (work == null) continue;
                    LOG.info("Finance execution claimed, errorCode={}, source={}, operationId={}", "NONE", TRACE_SOURCE, candidate.id());
                    try {
                        var input = work.request().input(); var terms = work.authorization().terms(); var payee = terms.payee();
                        var debitResult = accounts.debitAccounts(input.tenantId(), terms.targetDigest(), new PaymentAccountsPort.Request(payee.legalEntityId(), terms.amount().currency(), input.cashier()));
                        if (!(debitResult instanceof FinanceResult.Success<PaymentAccountsPort.Directory> debit)) { failed(work, debitResult); continue; }
                        var payeeResult = accounts.currentPayee(input.tenantId(), terms.targetDigest(), new PaymentAccountsPort.PayeeRequest(payee.legalEntityId(), payee.employeeId()));
                        if (!(payeeResult instanceof FinanceResult.Success<EmployeeAccountPort.Account> current)) { failed(work, payeeResult); continue; }
                        execution.finish(work, debit.value(), current.value(), Instant.now());
                    } catch (RuntimeException failed) {
                        execution.fail(work, PaymentExecutionRequest.Failure.INTERNAL_ERROR, false, Instant.now());
                        LOG.error("Payment request check failed, errorCode={}, requestId={}", "CHECK_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failed) { LOG.error("Payment request worker failed, errorCode={}, requestId={}", "WORKER_FAILURE", candidate.id()); }
            }
        }
    }
    private void failed(PaymentExecutionRequestService.Work work, FinanceResult<?> result) {
        boolean changed = result instanceof FinanceResult.Rejected<?>;
        var reason = result instanceof FinanceResult.Unavailable<?> unavailable ? PaymentExecutionRequest.Failure.valueOf(unavailable.failure().name()) : PaymentExecutionRequest.Failure.ACCOUNT_CHANGED;
        execution.fail(work, reason, changed, Instant.now());
    }
}
