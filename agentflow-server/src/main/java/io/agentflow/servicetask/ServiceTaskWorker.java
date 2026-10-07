package io.agentflow.servicetask;

import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * 网络调用发生在领取提交之后；异常后保留原号和未知事实，下一次轮询先查询。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceTaskWorker.class);
    private static final String TRACE_SOURCE = "service-task";
    private final JdbcServiceTaskOperationRepository operations;
    private final ServiceTaskOperationService service;
    private final ServiceTaskGateway gateway;
    /** 事务用例通过代理调用，工作线程不持有数据库事务跨越 HTTP。 */
    public ServiceTaskWorker(JdbcServiceTaskOperationRepository operations, ServiceTaskOperationService service, ServiceTaskGateway gateway) {
        this.operations = operations; this.service = service; this.gateway = gateway;
    }

    /** 单次最多十条，领取和推进共用原操作调度，不能重新创建替代任务。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Service task worker must run outside a transaction");
        for (var candidate : operations.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var claimed = service.claim(candidate.tenantId(), candidate.id(), Instant.now()); if (claimed == null) continue;
                    try {
                        var result = claimed.status() == ServiceTaskOperation.Status.EXECUTING ? gateway.execute(claimed.input()) : gateway.query(claimed.input());
                        service.finish(claimed, result, Instant.now());
                    } catch (RuntimeException failure) {
                        service.finish(claimed, new ServiceTaskGateway.Unavailable(ServiceTaskOperation.Failure.INTERNAL_ERROR), Instant.now());
                        LOG.error("Service task dispatch failed, errorCode={}, operationId={}", "INTERNAL_ERROR", candidate.id());
                    }
                } catch (RuntimeException failure) {
                    LOG.error("Service task worker failed, errorCode={}, operationId={}", "WORKER_FAILURE", candidate.id());
                    try { service.retryLater(candidate.tenantId(), candidate.id(), Instant.now()); }
                    catch (RuntimeException retryFailure) { LOG.error("Service task recovery failed, errorCode={}, operationId={}", "RECOVERY_UNAVAILABLE", candidate.id()); }
                }
            }
        }
    }
}
