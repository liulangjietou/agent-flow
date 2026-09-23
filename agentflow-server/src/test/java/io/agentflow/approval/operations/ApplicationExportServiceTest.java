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
class ApplicationExportServiceTest {
    private final Actor actor = new Actor("demo", "admin", Set.of("ADMIN"));
    private final ApplicationSearchPort.Query filters = new ApplicationSearchPort.Query("", "", "flow", 1L, "alice", null, null, 30, null, null);

    @Test
    void exactLimitIsCompleteAndExcessNeverBecomesATruncatedFile() throws Exception {
        var reader = mock(ApplicationSearchPort.class);
        var item = new ApplicationSearchPort.Item(UUID.randomUUID(), "001234567890123456789", "=1+1", "flow", 1, "alice", "DRAFT", 0, Instant.EPOCH, Instant.EPOCH);
        when(reader.search(eq("demo"), any())).thenReturn(Collections.nCopies(10_001, item), Collections.nCopies(10_000, item));
        var service = new ApplicationExportService(reader);
        assertThatThrownBy(() -> service.export(actor, filters)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("APPLICATION_EXPORT_LIMIT_EXCEEDED"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(actor, filters)))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(10_000);
            assertThat(book.getSheetAt(1).getRow(3).getCell(1).getStringCellValue()).isEqualTo("10000");
        }
        verify(reader, times(2)).search(eq("demo"), argThat(query -> query.limit() == 10_000 && query.beforeId() == null && query.beforeTime() == null && query.processKey().equals("flow")));
    }

    @Test
    void rejectsConcurrentGenerationAndReleasesCapacityAfterQueryFailure() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var reader = mock(ApplicationSearchPort.class);
        when(reader.search(anyString(), any())).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("read failed");
        }).thenReturn(List.of());
        var service = new ApplicationExportService(reader);
        var first = CompletableFuture.runAsync(() -> service.export(actor, filters));
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.export(actor, filters)).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("APPLICATION_EXPORT_BUSY"));
        } finally { release.countDown(); }
        assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export(actor, filters)))) { assertThat(book.getSheetAt(0).getLastRowNum()).isZero(); }
        verify(reader, times(2)).search(anyString(), any());
    }
}
