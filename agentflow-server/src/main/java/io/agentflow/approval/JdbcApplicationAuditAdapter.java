package io.agentflow.approval;

import io.agentflow.approval.mapper.ApplicationAuditAdapterMapper;
import io.agentflow.approval.service.ApplicationAuditPort;
import io.agentflow.common.JsonUtil;
import io.agentflow.integration.ApprovalWebhookEvents;
import io.agentflow.observability.DiagnosticContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 申请审计使用共享事件存储，并保留独立的申请聚合身份。
 *
 * @author owlzhangfq@gmail.com
 */
@Repository
public class JdbcApplicationAuditAdapter implements ApplicationAuditPort {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcApplicationAuditAdapter.class);
    private final ApplicationAuditAdapterMapper sqlMapper;
    private final JsonUtil json;
    private final ApprovalWebhookEvents webhooks;

    /** 使用申请事务共用的数据源记录审计。 */
    public JdbcApplicationAuditAdapter(
            ApplicationAuditAdapterMapper sqlMapper,
            JsonUtil json,
            ApprovalWebhookEvents webhooks) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.webhooks = webhooks;
    }

    @Override
    public void record(ApplicationOperation operation) {
        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        try (var scope =
                new DiagnosticContext(
                                DiagnosticContext.currentIdOr(eventId),
                                operation.tenantId(),
                                operation.businessNo(),
                                operation.processInstanceId(),
                                null)
                        .open()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", DiagnosticContext.currentIdOr(eventId));
            payload.put("businessNo", operation.businessNo());
            payload.put("action", operation.action().name());
            payload.put("actor", operation.actor());
            payload.put("applicationId", operation.applicationId().toString());
            payload.put("roundNo", operation.roundNo());
            payload.put("processInstanceId", operation.processInstanceId());
            payload.put("previousStatus", operation.previousStatus());
            payload.put("currentStatus", operation.currentStatus());
            payload.put("comment", operation.comment());
            sqlMapper.record(
                    UUID.randomUUID().toString(),
                    operation.tenantId(),
                    eventId,
                    operation.applicationId().toString(),
                    operation.aggregateVersion(),
                    operation.applicationId().toString(),
                    operation.action().name(),
                    operation.actor(),
                    json.write(payload),
                    Timestamp.from(occurredAt));
            LOG.info("Approval audit staged, errorCode={}, eventId={}", "NONE", eventId);
            webhooks.application(operation, eventId, occurredAt);
        }
    }
}
