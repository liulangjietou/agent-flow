package io.agentflow.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** 服务器私有 request attribute 连接安全过滤器、业务错误和最终响应。 */
public final class RequestTrace {
    private static final String TRACE_ATTRIBUTE = RequestTrace.class.getName() + ".traceId";
    private static final String TENANT_ATTRIBUTE = RequestTrace.class.getName() + ".tenantId";
    private RequestTrace() { }

    /** 每个请求只生成一次，错误和异步再分派保持原标识；不读取客户端追踪头。 */
    public static String id(HttpServletRequest request) {
        String current = (String) request.getAttribute(TRACE_ATTRIBUTE);
        if (current == null) {
            current = UUID.randomUUID().toString();
            request.setAttribute(TRACE_ATTRIBUTE, current);
        }
        return current;
    }

    /** 仅在认证成功后记录租户，不能使用提交参数或未经核验的声明。 */
    public static void authenticated(HttpServletRequest request, String tenantId) { request.setAttribute(TENANT_ATTRIBUTE, tenantId); }

    /** 请求终结或异步再分派恢复已经核验的诊断租户，不恢复业务身份。 */
    public static DiagnosticContext context(HttpServletRequest request) {
        return new DiagnosticContext(id(request), (String) request.getAttribute(TENANT_ATTRIBUTE));
    }
}
