package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;

import io.agentflow.finance.ExpensePaymentReturnPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;

/**
 * 后台读取原报销付款与真实退回依据，查询不会自动登记或发送资金。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePaymentReturnWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpensePaymentReturnWorker.class);
    private final JdbcExpensePaymentReturnCheckRepository checks;
    private final ExpensePaymentReturnService service;
    private final ExpensePaymentReturnPort port;
    /** 队列和短事务编排与外部传输分离。 */
    public ExpensePaymentReturnWorker(JdbcExpensePaymentReturnCheckRepository checks, ExpensePaymentReturnService service, ExpensePaymentReturnPort port) { this.checks = checks; this.service = service; this.port = port; }
    /** 每批至多十笔，外部异常不输出凭据正文。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense payment worker must execute outside a database transaction");
        for (var candidate : checks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "expense-payment-return-check", candidate.id().toString()).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        var input = claimed.input(); service.finish(claimed, port.query(input.tenantId(), input.targetDigest(), input.request()), Instant.now());
                    } catch (RuntimeException failure) {
                        service.fail(claimed, Instant.now()); LOG.error("Expense payment return read failed, errorCode={}, checkId={}", "EXPENSE_PAYMENT_RETURN_READ_FAILURE", candidate.id());
                    }
                } catch (RuntimeException failure) { LOG.error("Expense payment return worker failed, errorCode={}, checkId={}", "EXPENSE_PAYMENT_RETURN_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
}
