package io.agentflow.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 在 CORS 和安全链之前建立关联；完成日志仅包含路由模板、状态和耗时。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final String START_ATTRIBUTE = RequestTraceFilter.class.getName() + ".started";

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(START_ATTRIBUTE) == null) request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        response.setHeader(DiagnosticContext.HEADER, RequestTrace.id(request));
        boolean failed = false;
        try (var scope = RequestTrace.context(request).open()) {
            try { chain.doFilter(request, response); }
            catch (IOException | ServletException | RuntimeException | Error failure) { failed = true; throw failure; }
            finally {
                if (!request.isAsyncStarted()) {
                    try (var authenticated = RequestTrace.context(request).open()) {
                        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                        // 不记录 URI、查询、请求体或异常正文；未匹配路径可能携带私密内容。
                        LOG.info("HTTP request completed, errorCode={}, route={}, status={}, elapsedMs={}",
                                failed ? "UNHANDLED_REQUEST_FAILURE" : response.getStatus() >= 400 ? "HTTP_" + response.getStatus() : "NONE",
                                pattern == null ? "UNMAPPED" : pattern, failed ? 500 : response.getStatus(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - (Long) request.getAttribute(START_ATTRIBUTE)));
                    }
                }
            }
        }
    }

    /** 部分容器在原调用栈内分派错误，仍需恢复同一个响应头和作用域。 */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException { doFilterInternal(request, response, chain); }
}
