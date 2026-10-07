package io.agentflow.observability;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** 验证异常、嵌套、错误分派和重复使用线程的真实诊断边界。 */
class DiagnosticContextTest {
    @AfterEach void clear() { MDC.clear(); }

    @Test
    void completionLogContainsTraceAndTrustedTenantButNoRequestOrExceptionContent() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RequestTraceFilter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                event.prepareForDeferredProcessing(); super.append(event);
            }
        };
        appender.start(); logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/private-path-sentinel");
            request.setQueryString("secret=private-query-sentinel");
            request.addHeader("Authorization", "Bearer private-header-sentinel");
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> new RequestTraceFilter().doFilter(request, response, (input, output) -> {
                RequestTrace.authenticated(request, "tenant-a");
                throw new ServletException("private-exception-sentinel");
            })).isInstanceOf(ServletException.class);
            assertThat(appender.list).hasSize(1);
            var event = appender.list.get(0);
            assertThat(event.getMDCPropertyMap()).containsEntry("traceId", response.getHeader(DiagnosticContext.HEADER)).containsEntry("tenantId", "tenant-a");
            assertThat(event.getFormattedMessage()).contains("UNHANDLED_REQUEST_FAILURE", "route=UNMAPPED", "status=500").doesNotContain("private-");
            assertThat(event.getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void nestedScopesRestoreOnlyOwnedKeysEvenWhenExecutionFails() {
        MDC.put("unrelated", "kept");
        String parent = UUID.randomUUID().toString(), child = UUID.randomUUID().toString();
        try (var outer = new DiagnosticContext(parent, "tenant-a").open()) {
            assertThatThrownBy(() -> {
                try (var inner = new DiagnosticContext(child, "tenant-b").open()) {
                    assertThat(MDC.getCopyOfContextMap()).containsEntry("traceId", child).containsEntry("tenantId", "tenant-b");
                    MDC.put("new-unrelated", "also-kept");
                    throw new IllegalStateException("synthetic-private-message");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.getCopyOfContextMap()).containsEntry("traceId", parent).containsEntry("tenantId", "tenant-a");
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of("unrelated", "kept", "new-unrelated", "also-kept"));
    }

    @Test
    void requestExceptionAndErrorRedispatchKeepOneTraceAndDoNotPolluteTheReusedThread() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest("GET", "/private-path-value");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (input, output) -> {
            assertThat(MDC.get("traceId")).isEqualTo(response.getHeader(DiagnosticContext.HEADER));
            throw new ServletException("private-failure");
        })).isInstanceOf(ServletException.class);
        String trace = response.getHeader(DiagnosticContext.HEADER);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, request.getRequestURI());
        filter.doFilter(request, response, (input, output) -> assertThat(MDC.get("traceId")).isEqualTo(trace));
        assertThat(response.getHeader(DiagnosticContext.HEADER)).isEqualTo(trace);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/next"), next, (input, output) -> {
            assertThat(MDC.get("tenantId")).isNull();
            assertThat(MDC.get("traceId")).isNotEqualTo(trace);
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void capturedContextMustBeExplicitlyOpenedOnAnotherThreadAndThenCleared() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var scope = new DiagnosticContext(UUID.randomUUID().toString(), "tenant-a").open()) {
            var captured = DiagnosticContext.capture();
            assertThat(executor.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS)).isNull();
            assertThat(executor.submit(() -> {
                try (var restored = captured.open()) { return MDC.getCopyOfContextMap(); }
            }).get(5, TimeUnit.SECONDS)).containsEntry("traceId", captured.traceId()).containsEntry("tenantId", "tenant-a");
            assertThat(executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)).isNullOrEmpty();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void invalidTraceCannotReachMdcAndTenantLogCharactersAreBounded() {
        assertThatThrownBy(() -> new DiagnosticContext("forged\r\ntrace", "a").open()).isInstanceOf(IllegalArgumentException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        try (var scope = new DiagnosticContext(UUID.randomUUID().toString(), "a\r\nb\u2028" + "x".repeat(200)).open()) {
            assertThat(MDC.get("tenantId")).hasSize(128).doesNotContain("\r", "\n", "\u2028");
        }
        assertThat(DiagnosticContext.legacyId("event", "a", "1")).isNotEqualTo(DiagnosticContext.legacyId("event", "b", "1"));
    }
}
