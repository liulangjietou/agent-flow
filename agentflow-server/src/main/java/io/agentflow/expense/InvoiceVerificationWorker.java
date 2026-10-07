package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import static io.agentflow.expense.InvoiceVerificationJob.Failure;

/**
 * 原件读取和实际查验均在数据库事务之外，单次失败不自动重发。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceVerificationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InvoiceVerificationWorker.class);
    private static final String TRACE_SOURCE = "invoice-verification";
    private final JdbcInvoiceVerificationRepository jobs;
    private final InvoiceVerificationService execution;
    private final JdbcInvoiceOriginalRepository originals;
    private final InvoiceOriginalFiles files;
    private final InvoiceVerificationPort gateway;
    private final FinanceGatewayConfiguration configuration;

    /** 领取和完成通过独立事务代理，文件及 HTTP 等待不占用数据库锁。 */
    public InvoiceVerificationWorker(JdbcInvoiceVerificationRepository jobs, InvoiceVerificationService execution,
            JdbcInvoiceOriginalRepository originals, InvoiceOriginalFiles files, InvoiceVerificationPort gateway, FinanceGatewayConfiguration configuration) {
        this.jobs = jobs; this.execution = execution; this.originals = originals; this.files = files; this.gateway = gateway; this.configuration = configuration;
    }

    /** 有界扫描可直接用于重启恢复测试；未知本地异常仅记稳定分类。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Invoice worker must execute outside a database transaction");
        for (var candidate : jobs.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(),
                    TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var job = execution.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (job != null) {
                        LOG.info("Finance execution claimed, errorCode={}, source={}, operationId={}", "NONE", TRACE_SOURCE, candidate.id());
                        execute(job);
                    }
                } catch (RuntimeException failed) {
                    // 不把可能包含原件、票面或目标凭据的异常正文写入日志。
                    LOG.error("Invoice verification execution failed, errorCode={}, jobId={}", "WORKER_FAILURE", candidate.id());
                }
            }
        }
    }

    private void execute(InvoiceVerificationJob job) {
        var input = job.input();
        InvoiceVerificationPort.Request request;
        try {
            var original = originals.find(input.tenantId(), input.invoiceId()).orElseThrow(); original.requireReady();
            request = new InvoiceVerificationPort.Request(input.ownerId(), input.legalEntityId(), input.originalId(), input.originalDigest(),
                    original.format().mediaType(), files.read(original));
        } catch (DomainException unavailable) { execution.fail(job, Failure.ORIGINAL_UNAVAILABLE, Instant.now()); return; }
        var destination = configuration.destination(input.tenantId()).orElse(null);
        if (destination == null || !destination.digest(input.tenantId()).equals(input.targetDigest())) {
            execution.fail(job, destination == null ? Failure.NOT_CONFIGURED : Failure.TARGET_CHANGED, Instant.now()); return;
        }
        try { execution.finish(job, gateway.verify(input.tenantId(), request), Instant.now()); }
        catch (RuntimeException failed) {
            execution.fail(job, Failure.INTERNAL_ERROR, Instant.now());
            LOG.error("Invoice verification failed, errorCode={}, jobId={}", "INTERNAL_ERROR", input.id());
        }
    }
}
