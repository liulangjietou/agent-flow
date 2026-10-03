package io.agentflow.finance.callback;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单个记录读取异常也必须隔离，不能中断同批其余已认证回调。
 * @author owlzhangfq@gmail.com
 */
class PaymentCallbackWorkerTest {
    @Test void unavailableRecordDoesNotStarveOtherCandidates() {
        var store = mock(JdbcPaymentCallbackRepository.class); var service = mock(PaymentCallbackService.class);
        var first = new JdbcPaymentCallbackRepository.Candidate("demo", UUID.randomUUID());
        var second = new JdbcPaymentCallbackRepository.Candidate("demo", UUID.randomUUID());
        var input = new PaymentCallbackVerifier.Verified("evt_1", "a".repeat(64), "b".repeat(64),
                new PaymentCallbackVerifier.Signal(1, "payment.changed", "demo", PaymentCallbackVerifier.Kind.EMPLOYEE, UUID.randomUUID(), "c".repeat(64), 1));
        when(store.due(any())).thenReturn(List.of(first, second));
        when(store.get(first.tenantId(), first.id())).thenThrow(new IllegalStateException("private-record-data"));
        when(store.get(second.tenantId(), second.id())).thenReturn(PaymentCallback.receive(input, Instant.now()));
        new PaymentCallbackWorker(store, service).poll();
        verify(service).process(eq(second), any()); verify(service, never()).process(eq(first), any());
    }
}
