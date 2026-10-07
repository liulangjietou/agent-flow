package io.agentflow.signature;

import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/**
 * 工作器只执行已提交的领取；进程中断后依赖持久状态恢复，不重新创建签署或原件。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SignatureWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SignatureWorker.class);
    private static final String TRACE_SOURCE = "signature";
    private final JdbcSignatureOperationRepository operations;
    private final JdbcSignatureEvidenceRepository evidence;
    private final SignatureOperationService service;
    private final SignatureGateway gateway;
    private final Clock clock;
    /** 事务服务通过代理调用，外部服务和文件系统不占用业务数据库事务。 */
    @Autowired
    public SignatureWorker(JdbcSignatureOperationRepository operations, JdbcSignatureEvidenceRepository evidence,
            SignatureOperationService service, SignatureGateway gateway) { this(operations, evidence, service, gateway, Clock.systemUTC()); }
    SignatureWorker(JdbcSignatureOperationRepository operations, JdbcSignatureEvidenceRepository evidence,
            SignatureOperationService service, SignatureGateway gateway, Clock clock) {
        this.operations = operations; this.evidence = evidence; this.service = service; this.gateway = gateway; this.clock = clock;
    }
    /** 单次有界扫描；过期发送领取恢复为原号查询，保存中的文件继续沿用原内容编号。 */
    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Signature worker must run outside a transaction");
        for (var candidate : operations.due(clock.instant())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString()).open()) {
                try {
                    var claim = service.claim(candidate.tenantId(), candidate.id(), clock.instant()); if (claim == null) continue;
                    dispatch(claim);
                } catch (RuntimeException failure) {
                    // 不记录异常原文，避免第三方响应或凭据进入日志；持久租约保留恢复方向。
                    LOG.error("Signature worker failed, errorCode={}, operationId={}", "SIGNATURE_WORKER_FAILURE", candidate.id());
                }
            }
        }
    }
    private void dispatch(SignatureOperation claim) {
        if (claim.status() == SignatureOperation.Status.FETCHING_FILES) {
            var proof = evidence.forReceipt(claim).evidence();
            for (var file : claim.artifacts()) {
                if (Thread.currentThread().isInterrupted()) return;
                var result = gateway.collect(claim, proof, file);
                if (result instanceof SignatureGateway.Stored stored && stored.file().equals(file)) {
                    if (!service.confirmFile(claim, file, clock.instant())) return;
                } else {
                    service.finishFiles(claim, result instanceof SignatureGateway.Unavailable unavailable ? unavailable.failure() : SignatureOperation.Failure.ARTIFACT_MISMATCH, clock.instant());
                    return;
                }
            }
            service.finishFiles(claim, null, clock.instant());
        } else {
            var result = claim.status() == SignatureOperation.Status.SENDING ? gateway.submit(claim) : gateway.query(claim);
            service.finish(claim, result, clock.instant());
        }
    }
}
