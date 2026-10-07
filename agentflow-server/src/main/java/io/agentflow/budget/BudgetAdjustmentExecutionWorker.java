package io.agentflow.budget;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原子预算执行器只发送已保存的授权，结果未知时严格选择原号查询。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentExecutionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(BudgetAdjustmentExecutionWorker.class);
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final BudgetAdjustmentExecutionService service;
    private final BudgetAdjustmentPort gateway;
    /** 网络等待位于领取和结果保存之间，不持有数据库事务。 */
    public BudgetAdjustmentExecutionWorker(JdbcBudgetAdjustmentOperationRepository operations, BudgetAdjustmentExecutionService service, BudgetAdjustmentPort gateway) {
        this.operations = operations; this.service = service; this.gateway = gateway;
    }
    /** 有界领取原持久任务，外部异常不导致新的命令或同轮自动重发。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Budget execution worker must run outside a database transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var scope = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), "budget-adjustment-operation", candidate.id().toString())
                    .withBusiness(candidate.businessNo(), candidate.processInstanceId(), null).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    String instance = candidate.businessNo() == null ? null : operations.findProcessInstance(claimed.command().source());
                    try (var business = DiagnosticContext.capture().withBusiness(candidate.businessNo(), instance, null).open()) {
                        process(candidate, claimed);
                    }
                } catch (RuntimeException failed) { LOG.error("Budget execution worker failed, errorCode={}, operationId={}", "EXECUTION_WORKER_FAILURE", candidate.id()); }
            }
        }
    }
    /** 失败回写再次异常时也在原轮次作用域内记录，避免外层日志丢失实例。 */
    private void process(JdbcBudgetAdjustmentOperationRepository.Candidate candidate, BudgetAdjustmentOperation claimed) {
        try {
            try {
                LOG.info("Financial review execution claimed, errorCode={}, source={}, operationId={}", "NONE", "budget-adjustment-operation", candidate.id());
                var result = claimed.status() == BudgetAdjustmentOperation.Status.EXECUTING
                        ? gateway.execute(claimed.command()) : gateway.query(claimed.command());
                service.finish(claimed, result, Instant.now());
            } catch (RuntimeException failed) {
                service.fail(claimed, Instant.now()); LOG.error("Budget execution failed, errorCode={}, operationId={}", "EXECUTION_FAILURE", candidate.id());
            }
        } catch (RuntimeException failed) {
            LOG.error("Budget execution worker failed, errorCode={}, operationId={}", "EXECUTION_WORKER_FAILURE", candidate.id());
        }
    }

}
