package io.agentflow.integration;

import io.agentflow.approval.service.ApplicationAuditPort.ApplicationOperation;
import io.agentflow.approval.service.TaskAuditPort.TaskOperation;
import io.agentflow.common.JsonUtil;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将同事务的审批操作事实投影为精简集成事件，不读取历史审计补发。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ApprovalWebhookEvents {
    private final WebhookTargets targets;
    private final JdbcWebhookStore store;
    private final JsonUtil json;

    /** 注入部署目录和与审批共用数据源的 outbox。 */
    public ApprovalWebhookEvents(WebhookTargets targets, JdbcWebhookStore store, JsonUtil json) {
        this.targets = targets; this.store = store; this.json = json;
    }

    /** 创建与修改草稿不产生外部事件，提交、撤回和作废按实际操作排队。 */
    public void application(ApplicationOperation operation, String eventId, Instant occurredAt) {
        String type = switch (operation.action()) {
            case SUBMIT -> "ApplicationSubmitted";
            case WITHDRAW -> "ApplicationWithdrawn";
            case CANCEL -> "ApplicationCancelled";
            default -> null;
        };
        if (type == null) return;
        append(operation.tenantId(), eventId, type, "Application", operation.applicationId().toString(),
                operation.applicationId(), operation.aggregateVersion(), occurredAt, eventId,
                payload(operation.applicationId(), operation.roundNo(), operation.actor(), operation.action().name(), operation.previousStatus(), operation.currentStatus()));
    }

    /** 会签中的单人同意只产生任务事件，申请结论必须来自真实状态转换。 */
    public void task(TaskOperation operation, String eventId, Instant occurredAt) {
        var payload = payload(operation.applicationId(), operation.roundNo(), operation.actor(), operation.action(), operation.previousStatus(), operation.currentStatus());
        payload.put("taskId", operation.taskId());
        append(operation.tenantId(), eventId, "TaskActionAccepted", "Task", operation.taskId(), operation.applicationId(),
                operation.aggregateVersion(), occurredAt, eventId, payload);
        if (Objects.equals(operation.previousStatus(), operation.currentStatus())) return;
        String type = switch (operation.currentStatus()) {
            case APPROVED -> "ApplicationApproved";
            case RETURNED -> "ApplicationReturned";
            case REJECTED -> "ApplicationRejected";
            default -> null;
        };
        if (type != null) append(operation.tenantId(), UUID.randomUUID().toString(), type, "Application", operation.applicationId().toString(),
                operation.applicationId(), operation.aggregateVersion(), occurredAt, eventId, payload);
    }

    private Map<String, Object> payload(UUID id, int round, String actor, String action, Object previous, Object current) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", id); payload.put("roundNo", round); payload.put("actor", actor); payload.put("action", action);
        payload.put("previousStatus", previous); payload.put("currentStatus", current);
        return payload;
    }
    private void append(String tenant, String eventId, String type, String aggregateType, String aggregateId, UUID applicationId,
                        long version, Instant occurredAt, String traceId, Map<String, Object> payload) {
        var enabled = targets.enabled(tenant);
        if (enabled.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId); event.put("eventType", type); event.put("tenantId", tenant);
        event.put("aggregateType", aggregateType); event.put("aggregateId", aggregateId); event.put("aggregateVersion", version);
        event.put("occurredAt", occurredAt); event.put("traceId", traceId); event.put("payloadVersion", 1); event.put("payload", payload);
        String body = json.write(event);
        for (var target : enabled) store.append(tenant, target, eventId, type, applicationId, version, body, occurredAt);
    }
}
