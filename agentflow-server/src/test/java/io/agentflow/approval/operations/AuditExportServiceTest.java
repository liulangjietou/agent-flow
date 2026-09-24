package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 导出边界覆盖完整上限、拒绝截断及并发/失败后的容量释放。
 * @author owlzhangfq@gmail.com
 */
class AuditExportServiceTest {
    private final Actor actor = new Actor("demo", "admin", Set.of("ADMIN"));
    private final AuditSearchPort.Query filters = new AuditSearchPort.Query("关键词", "alice", "APPROVE", "Task", null, null, null, 30, null, null);

    @Test
    void exactLimitIsCompleteAndExcessNeverBecomesATruncatedFile() throws Exception {
        var reader = mock(AuditSearchPort.class);
        var item = new AuditSearchPort.Item(UUID.randomUUID(), "001234567890123456789", "Task", "=1+1", 1, "APPROVE", "alice", Instant.EPOCH, null, null, null);
        when(reader.search(eq(actor.tenantId()), any())).thenReturn(Collections.nCopies(10_001, item), Collections.nCopies(10_000, item));
        var service = new AuditExportService(reader);
        assertThatThrownBy(() -> service.export(actor, filters)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("AUDIT_EXPORT_LIMIT_EXCEEDED"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(actor, filters)))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(10_000);
            assertThat(book.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("10000");
        }
        verify(reader, times(2)).search(eq(actor.tenantId()), argThat(query -> query.limit() == 10_000 && query.beforeId() == null && query.beforeTime() == null && query.actor().equals("alice") && query.action().equals("APPROVE") && query.source().equals("Task") && query.text().equals("关键词")));
    }

    @Test
    void rejectsConcurrentGenerationAndReleasesCapacityAfterQueryFailure() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var reader = mock(AuditSearchPort.class);
        when(reader.search(anyString(), any())).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("read failed");
        }).thenReturn(List.of());
        var service = new AuditExportService(reader);
        var first = CompletableFuture.runAsync(() -> service.export(actor, filters));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.export(actor, filters)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("AUDIT_EXPORT_BUSY"));
        } finally { release.countDown(); }
        assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(actor, filters)))) { assertThat(book.getSheetAt(0).getLastRowNum()).isZero(); }
        verify(reader, times(2)).search(anyString(), any());
    }
}
