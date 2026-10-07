package io.agentflow.organization;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 有界扫描持久批次，来源读取在领取与接收事务之间，接收后不自动应用。
 * @author owlzhangfq@gmail.com
 */
@Service
public final class OrganizationSyncWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OrganizationSyncWorker.class);
    private static final String TRACE_SOURCE = "organization-sync";
    private final JdbcOrganizationSyncRepository batches;
    private final OrganizationSyncService service;
    private final HttpOrganizationSyncSource source;

    /** 只组合同步读取，不持有人工应用用例。 */
    public OrganizationSyncWorker(JdbcOrganizationSyncRepository batches, OrganizationSyncService service, HttpOrganizationSyncSource source) {
        this.batches = batches; this.service = service; this.source = source;
    }

    /** 多进程竞争由持久状态和锁解决；未到期读取不重新发送。 */
    public void poll() {
        for (var candidate : batches.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var claim = service.claim(candidate.tenantId(), candidate.id(), Instant.now());
                    if (claim == null || !service.sendable(claim, Instant.now())) continue;
                    var result = source.read(claim.context(), claim.leaseUntil());
                    service.finish(claim, result, Instant.now());
                } catch (RuntimeException failure) {
                    LOG.error("Organization synchronization failed, errorCode={}, batchId={}, exceptionType={}",
                            "WORKER_FAILURE", candidate.id(), failure.getClass().getSimpleName());
                }
            }
        }
    }
}
