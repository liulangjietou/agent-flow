package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 账户和资金请求都在事务外执行，账户检查失败与资金结果未知采用不同恢复路径。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PaymentOperationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentOperationWorker.class);
    private final JdbcPaymentOperationRepository operations;
    private final PaymentOperationService execution;
    private final PaymentAccountsPort accounts;
    private final PaymentSystemPort payments;
    /** 数据库事务只在独立服务代理中领取和确认，不跨网络等待。 */
    public PaymentOperationWorker(JdbcPaymentOperationRepository operations, PaymentOperationService execution, PaymentAccountsPort accounts, PaymentSystemPort payments) {
        this.operations = operations; this.execution = execution; this.accounts = accounts; this.payments = payments;
    }
    /** 每批最多十笔，恢复使用持久原输入，不根据当前配置重建命令。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Payment worker must execute outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var claimed = execution.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                if (claimed.status() == PaymentOperation.Status.CHECKING) claimed = checkAccounts(claimed);
                if (claimed == null) continue;
                try {
                    var input = claimed.input(); FinanceResult<PaymentObservation> result;
                    if (claimed.status() == PaymentOperation.Status.SENDING) {
                        claimed.requireSendAt(Instant.now()); result = payments.execute(input.targetDigest(), input.command());
                    } else result = payments.query(input.targetDigest(), input.command());
                    execution.finish(claimed, result, Instant.now());
                } catch (RuntimeException failed) {
                    var reason = failed instanceof DomainException domain && "PAYMENT_AUTHORIZATION_EXPIRED".equals(domain.code())
                            ? PaymentOperation.Failure.AUTHORIZATION_EXPIRED : PaymentOperation.Failure.INTERNAL_ERROR;
                    execution.fail(claimed, reason, Instant.now());
                    LOG.error("Payment dispatch failed, errorCode={}, authorizationId={}", reason, candidate.id());
                }
            } catch (RuntimeException failed) { LOG.error("Payment worker failed, errorCode={}, authorizationId={}", "WORKER_FAILURE", candidate.id()); }
        }
    }
    private PaymentOperation checkAccounts(PaymentOperation checking) {
        try {
            var input = checking.input(); var command = input.command(); var payee = command.payee();
            var debitResult = accounts.debitAccounts(command.tenantId(), input.targetDigest(), new PaymentAccountsPort.Request(payee.legalEntityId(), command.amount().currency(), command.authorization().executedBy()));
            if (!(debitResult instanceof FinanceResult.Success<PaymentAccountsPort.Directory> debit)) { accountFailure(checking, debitResult); return null; }
            var payeeResult = accounts.currentPayee(command.tenantId(), input.targetDigest(), new PaymentAccountsPort.PayeeRequest(payee.legalEntityId(), payee.employeeId()));
            if (!(payeeResult instanceof FinanceResult.Success<EmployeeAccountPort.Account> current)) { accountFailure(checking, payeeResult); return null; }
            return execution.readyToSend(checking, debit.value(), current.value(), Instant.now());
        } catch (RuntimeException failed) {
            boolean changed = failed instanceof DomainException domain && ("PAYMENT_ACCOUNT_CHANGED".equals(domain.code()) || "PAYMENT_DEBIT_ACCOUNT_UNAVAILABLE".equals(domain.code()));
            execution.checkFailed(checking, changed ? PaymentOperation.Failure.ACCOUNT_CHANGED : PaymentOperation.Failure.INTERNAL_ERROR, changed, Instant.now());
            LOG.error("Payment account check failed, errorCode={}, authorizationId={}", changed ? "ACCOUNT_CHANGED" : "CHECK_FAILURE", checking.input().command().id());
            return null;
        }
    }
    private void accountFailure(PaymentOperation checking, FinanceResult<?> result) {
        boolean changed = result instanceof FinanceResult.Rejected<?>;
        var reason = result instanceof FinanceResult.Unavailable<?> unavailable ? PaymentOperation.Failure.valueOf(unavailable.failure().name()) : PaymentOperation.Failure.ACCOUNT_CHANGED;
        execution.checkFailed(checking, reason, changed, Instant.now());
    }
}
