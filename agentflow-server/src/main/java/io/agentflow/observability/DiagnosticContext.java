package io.agentflow.observability;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 仅承载诊断关联，不提供身份或业务授权；显式作用域防止线程池串号。
 * @author owlzhangfq@gmail.com
 */
public record DiagnosticContext(String traceId, String tenantId, String businessNo, String processInstanceId, String taskId) {
    public static final String TRACE_ID = "traceId";
    public static final String TENANT_ID = "tenantId";
    public static final String BUSINESS_NO = "businessNo";
    public static final String PROCESS_INSTANCE_ID = "processInstanceId";
    public static final String TASK_ID = "taskId";
    public static final String HEADER = "X-Trace-Id";
    private static final Pattern TRACE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_LOG_IDENTIFIER = 128;

    /** 入口与旧队列没有已授权的业务上下文，不继承复用线程中的业务标识。 */
    public DiagnosticContext(String traceId, String tenantId) {
        this(traceId, tenantId, null, null, null);
    }

    /** 捕获已建立的诊断上下文；没有请求的独立执行生成自己的追踪标识。 */
    public static DiagnosticContext capture() {
        return new DiagnosticContext(currentIdOr(UUID.randomUUID().toString()), MDC.get(TENANT_ID),
                MDC.get(BUSINESS_NO), MDC.get(PROCESS_INSTANCE_ID), MDC.get(TASK_ID));
    }

    /** 调用方完成授权或原执行绑定校验后，以实际业务事实建立子作用域，不继承父单的业务标识。 */
    public static DiagnosticContext forBusiness(String tenantId, String businessNo, String processInstanceId, String taskId) {
        return new DiagnosticContext(currentIdOr(UUID.randomUUID().toString()), tenantId, businessNo, processInstanceId, taskId);
    }

    /** 将原已授权持久执行的业务定位组合到恢复上下文，不从当前线程补齐缺失字段。 */
    public DiagnosticContext withBusiness(String businessNo, String processInstanceId, String taskId) {
        return new DiagnosticContext(traceId, tenantId, businessNo, processInstanceId, taskId);
    }

    /** 在同事务审计和事件之间保留请求来源；旧调用没有请求时沿用事件标识。 */
    public static String currentIdOr(String fallback) {
        String current = MDC.get(TRACE_ID);
        return validTrace(current) ? current : fallback;
    }

    /** 旧持久记录没有来源时生成稳定的独立执行标识，不伪造原请求来源。 */
    public static String legacyId(String kind, String tenant, String id) {
        return UUID.nameUUIDFromBytes((kind + "\u0000" + tenant + "\u0000" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 从持久队列恢复来源；旧记录或损坏字段仅使用稳定的独立标识，不回写来源。 */
    public static DiagnosticContext restored(String traceId, String tenantId, String kind, String id) {
        return new DiagnosticContext(validTrace(traceId) ? traceId : legacyId(kind, tenantId, id), tenantId);
    }

    /** 持久化追踪字段按有界 UUID 校验，不能把外部正文写进响应头或日志。 */
    public static boolean validTrace(String value) { return value != null && TRACE.matcher(value).matches(); }

    /** 建立当前线程作用域；关闭后恢复嵌套前的关联，不影响其他 MDC 键。 */
    public Scope open() {
        if (!validTrace(traceId)) throw new IllegalArgumentException("Invalid diagnostic trace identifier");
        var previous = new HashMap<String, String>();
        previous.put(TRACE_ID, MDC.get(TRACE_ID));
        previous.put(TENANT_ID, MDC.get(TENANT_ID));
        previous.put(BUSINESS_NO, MDC.get(BUSINESS_NO));
        previous.put(PROCESS_INSTANCE_ID, MDC.get(PROCESS_INSTANCE_ID));
        previous.put(TASK_ID, MDC.get(TASK_ID));
        MDC.put(TRACE_ID, traceId);
        put(TENANT_ID, safeIdentifier(tenantId));
        put(BUSINESS_NO, safeIdentifier(businessNo));
        put(PROCESS_INSTANCE_ID, safeIdentifier(processInstanceId));
        put(TASK_ID, safeIdentifier(taskId));
        return new Scope(previous);
    }

    private static String safeIdentifier(String value) {
        if (value == null) return null;
        // 诊断字段不允许伪造键分隔符或改变文本方向；业务和审计原值保持不变。
        String safe = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Z}=]", "_");
        return safe.substring(0, Math.min(MAX_LOG_IDENTIFIER, safe.length()));
    }
    private static void put(String key, String value) { if (value == null) MDC.remove(key); else MDC.put(key, value); }

    /**
     * 作用域只在创建它的执行线程内使用，不向异步线程传递可关闭实例。
     * @author owlzhangfq@gmail.com
     */
    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;
        private boolean closed;
        private Scope(Map<String, String> previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            previous.forEach(DiagnosticContext::put);
            closed = true;
        }
    }
}
