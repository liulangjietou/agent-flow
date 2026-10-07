package io.agentflow.expense;

import io.agentflow.observability.DiagnosticContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 归档下载使用另一线程写文件，验证实际控制器保留诊断关联且不传递业务身份。
 * @author owlzhangfq@gmail.com
 */
class ExpenseArchiveTraceTest {
    @Test
    void actualDownloadCallbackKeepsRequestTraceWithoutLeavingItOnTheStreamingThread() throws Exception {
        var workspace = mock(ExpenseArchiveWorkspace.class);
        var files = mock(ExpenseArchiveFiles.class);
        var entry = mock(JdbcExpenseArchiveRepository.Entry.class, RETURNS_DEEP_STUBS);
        when(entry.archive().manifest().source().roundNo()).thenReturn(1);
        UUID id = UUID.randomUUID();
        when(workspace.download(id, Map.of())).thenReturn(entry);
        var observed = new AtomicReference<Map<String, String>>();
        doAnswer(call -> { observed.set(MDC.getCopyOfContextMap()); return null; }).when(files).write(eq(entry), any());
        String trace = UUID.randomUUID().toString();
        var executor = Executors.newSingleThreadExecutor();
        try (var scope = new DiagnosticContext(trace, "demo").open()) {
            var body = new ExpenseArchiveController(workspace, files).download(id, Map.of()).getBody();
            executor.submit(() -> { body.writeTo(new ByteArrayOutputStream()); return null; }).get(5, TimeUnit.SECONDS);
            assertThat(observed.get()).containsEntry("traceId", trace).containsEntry("tenantId", "demo");
            assertThat(executor.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS)).isNullOrEmpty();
            assertThat(MDC.get("traceId")).isEqualTo(trace);
        } finally { executor.shutdownNow(); }
    }
}
