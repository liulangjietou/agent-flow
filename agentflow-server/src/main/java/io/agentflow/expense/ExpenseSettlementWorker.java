package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 本地结算调度与银行执行独立；重启补登只重读原事实，不重发付款。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseSettlementWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseSettlementWorker.class);
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseSettlementRegistration registration;
    private final ExpenseSettlementService execution;
    private JdbcExpenseSettlementRepository.RecoveryCandidate recoveryCursor;

    /** 领取后的业务冲突在事务回滚后另记阻塞，数据库暂时异常保留队列以便恢复。 */
    public ExpenseSettlementWorker(JdbcExpenseSettlementRepository settlements, ExpenseSettlementRegistration registration, ExpenseSettlementService execution) {
        this.settlements = settlements; this.registration = registration; this.execution = execution;
    }

    /** 两类工作各有界十笔；未满足条件的旧凭证不会永久占据第一页。 */
    public synchronized void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Expense settlement worker must run outside a database transaction");
        var candidates = settlements.recoveryCandidates(recoveryCursor);
        if (candidates.isEmpty()) recoveryCursor = null;
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) return;
            try { registration.recover(candidate); }
            catch (RuntimeException failed) { log(candidate.reportId(), failed); }
            recoveryCursor = candidate;
        }
        for (var candidate : settlements.pending()) {
            if (Thread.currentThread().isInterrupted()) return;
            try { execution.consume(candidate); }
            catch (DomainException problem) {
                try { if (!"CONCURRENCY_CONFLICT".equals(problem.code())) execution.block(candidate, problem.code()); }
                catch (RuntimeException failed) { log(candidate.reportId(), failed); }
            }
            catch (RuntimeException failed) { log(candidate.reportId(), failed); }
        }
    }
    private static void log(java.util.UUID id, RuntimeException failed) {
        LOG.error("Expense settlement failed, errorCode={}, reportId={}", failed instanceof DomainException problem ? problem.code() : "SETTLEMENT_FAILURE", id);
    }
}
