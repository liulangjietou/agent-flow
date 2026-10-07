package io.agentflow.event;

import io.agentflow.observability.DiagnosticContext;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 有界轮询持久收件，不依赖内存任务或外部请求保持连接。
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(name = "agentflow.events.worker-enabled", havingValue = "true", matchIfMissing = true)
public class EventInboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(EventInboxWorker.class);
    private static final String TRACE_SOURCE = "event-inbox";
    private final EventInboxRepository inbox;
    private final EventInboxService service;
    /** 每条消息由独立短事务处理，单条失败不阻断整批。 */
    public EventInboxWorker(EventInboxRepository inbox, EventInboxService service) { this.inbox = inbox; this.service = service; }
    /** 错误只记录稳定分类和内部标识，不输出外部正文、凭据或数据库异常文本。 */
    @Scheduled(fixedDelayString = "${agentflow.events.poll-delay-ms:1000}")
    public void poll() {
        for (var candidate : inbox.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            var origin = DiagnosticContext.restored(candidate.traceId(), candidate.tenantId(), TRACE_SOURCE, candidate.id().toString())
                    .withBusiness(candidate.businessNo(), null, null);
            try (var trace = origin.open()) {
                Long version = null;
                try {
                    var item = inbox.get(candidate.tenantId(), candidate.id());
                    version = item.version();
                    String instance = candidate.businessNo() == null ? null : inbox.findProcessInstance(item).orElse(null);
                    try (var business = origin.withBusiness(candidate.businessNo(), instance, null).open()) {
                        process(candidate, version);
                    }
                } catch (RuntimeException failure) { failed(candidate, version); }
            }
        }
    }

    private void process(EventInboxRepository.Candidate candidate, long version) {
        try {
            LOG.info("Integration execution started, errorCode={}, source={}, operationId={}", "NONE", TRACE_SOURCE, candidate.id());
            service.process(candidate, Instant.now());
        } catch (RuntimeException failure) { failed(candidate, version); }
    }

    private void failed(EventInboxRepository.Candidate candidate, Long version) {
        LOG.error("Event processing failed, errorCode={}, eventInboxId={}", "EVENT_PROCESSING_FAILED", candidate.id());
        try { if (version != null) service.failed(candidate, version, Instant.now()); }
        catch (RuntimeException unavailable) { LOG.error("Event recovery failed, errorCode={}, eventInboxId={}", "EVENT_RECOVERY_UNAVAILABLE", candidate.id()); }
    }
}
