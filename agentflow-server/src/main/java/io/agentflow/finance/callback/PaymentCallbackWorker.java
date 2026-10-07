package io.agentflow.finance.callback;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 有界回调恢复只登记原号查询，实际银行网络调用由既有付款工作器处理。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.payment-callbacks.worker-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentCallbackWorker {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentCallbackWorker.class);
    private static final String TRACE_SOURCE = "payment-callback";
    private final JdbcPaymentCallbackRepository callbacks;
    private final PaymentCallbackService service;
    /** 收件箱和业务短事务由独立服务编排。 */
    public PaymentCallbackWorker(JdbcPaymentCallbackRepository callbacks, PaymentCallbackService service) { this.callbacks = callbacks; this.service = service; }

    /** 单条故障按原版本记录；异常原文可能包含数据库内容，因此仅输出稳定错误码和标识。 */
    @Scheduled(fixedDelayString = "${agentflow.payment-callbacks.poll-delay-ms:1000}")
    public void poll() {
        for (var candidate : callbacks.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            try (var trace = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString()).open()) {
                Long version = null;
                try {
                    version = callbacks.get(candidate.tenantId(), candidate.id()).version();
                    service.process(candidate, Instant.now());
                }
                catch (RuntimeException failure) {
                    LOG.error("Payment callback processing failed, errorCode={}, callbackId={}", "CALLBACK_PROCESSING_FAILED", candidate.id());
                    try { if (version != null) service.failed(candidate, version, Instant.now()); }
                    catch (RuntimeException unavailable) { LOG.error("Payment callback recovery failed, errorCode={}, callbackId={}", "CALLBACK_RECOVERY_UNAVAILABLE", candidate.id()); }
                }
            }
        }
    }
}
