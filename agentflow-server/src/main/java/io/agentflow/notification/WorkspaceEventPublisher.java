package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 面向当前认证主体的轻量 SSE 事件推送器；只下发无敏感正文的刷新信号，业务数据仍由原授权接口拉取。
 * @author owlzhangfq@gmail.com
 */
@Component
public class WorkspaceEventPublisher {
    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int MAX_CONNECTIONS_PER_USER = 5;
    private final JsonUtil json;
    private final ConcurrentHashMap<SubscriberKey, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 复用平台统一 JSON 序列化。 */
    public WorkspaceEventPublisher(JsonUtil json) { this.json = json; }

    /** 为当前已认证主体建立有界 SSE 订阅；超出单用户连接上限时主动关闭最旧连接。 */
    public SseEmitter subscribe(Actor actor) {
        var key = new SubscriberKey(actor.tenantId(), actor.userId());
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        var list = subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        while (list.size() > MAX_CONNECTIONS_PER_USER) {
            SseEmitter oldest = list.remove(0);
            try { oldest.complete(); } catch (RuntimeException ignored) { }
        }
        Runnable cleanup = () -> remove(key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        send(key, emitter, EventType.CONNECTED);
        return emitter;
    }

    /** 在事务提交后推送轻量刷新信号，避免未提交或回滚事务触发误刷新。 */
    public void publishAfterCommit(String tenantId, String userId, EventType type) {
        if (tenantId == null || userId == null || type == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(tenantId, userId, type); }
            });
        } else {
            publish(tenantId, userId, type);
        }
    }

    /** 立即向指定租户主体的在线连接广播刷新信号。 */
    public void publish(String tenantId, String userId, EventType type) {
        var key = new SubscriberKey(tenantId, userId);
        var list = subscribers.get(key);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) send(key, emitter, type);
    }

    /** 暴露当前在线订阅数，便于测试与连接回收核验。 */
    public int activeConnections(String tenantId, String userId) {
        var list = subscribers.get(new SubscriberKey(tenantId, userId));
        return list == null ? 0 : list.size();
    }

    private void send(SubscriberKey key, SseEmitter emitter, EventType type) {
        try {
            String payload = json.write(Map.of("type", type.name(), "at", Instant.now().toString()));
            emitter.send(SseEmitter.event().name(type.name().toLowerCase(java.util.Locale.ROOT)).data(payload));
        } catch (IOException | IllegalStateException disconnected) {
            remove(key, emitter);
        }
    }

    private void remove(SubscriberKey key, SseEmitter emitter) {
        var list = subscribers.get(key);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(key, list);
        }
    }

    /**
     * 轻量刷新事件分类，绝不包含业务单号、金额或表单正文。
     * @author owlzhangfq@gmail.com
     */
    public enum EventType { CONNECTED, INBOX_UPDATED, TASK_UPDATED, AGENT_UPDATED }

    /**
     * 严格按租户与主体双键隔离订阅通道。
     * @author owlzhangfq@gmail.com
     */
    private record SubscriberKey(String tenantId, String userId) { }
}
