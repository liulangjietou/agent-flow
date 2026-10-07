package io.agentflow.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调度逐条隔离读取和推进异常，迟到失败只携带原处理修订。
 * @author owlzhangfq@gmail.com
 */
class EventInboxWorkerTest {
    @Test void aBrokenRecordDoesNotPreventTheNextEventFromBeingProcessed() {
        var store = mock(EventInboxRepository.class); var service = mock(EventInboxService.class);
        var first = new EventInboxRepository.Candidate("demo", UUID.randomUUID(), null); var second = new EventInboxRepository.Candidate("demo", UUID.randomUUID(), null);
        when(store.due(any())).thenReturn(List.of(first, second));
        when(store.get(first.tenantId(), first.id())).thenThrow(new IllegalStateException("private-record-content"));
        var input = new ReceivedEvent("evt", "a".repeat(64), 1, new EventSignal(1, "demo", "erp", "GoodsAccepted", UUID.randomUUID(), 1, "wait", "accepted", 1));
        when(store.get(second.tenantId(), second.id())).thenReturn(EventInboxItem.receive(input, Instant.now()));
        doThrow(new IllegalStateException("private-response")).when(service).process(eq(second), any());
        new EventInboxWorker(store, service).poll();
        verify(service, never()).process(eq(first), any()); verify(service).process(eq(second), any());
        verify(service).failed(eq(second), eq(1L), any()); verify(service, never()).failed(eq(first), anyLong(), any());
    }
}
