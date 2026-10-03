package io.agentflow.approval.process;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单条失败不能挡住后续代理，扫描失败保留游标，扫到尾部后重新检查未能发送的候选。
 * @author owlzhangfq@gmail.com
 */
class ApprovalProxyNotificationSchedulerTest {
    @Test void preservesCursorAcrossScanFailureAndRevisitsUnsentCandidatesAfterLastPage() {
        var notifications = mock(FlowableApprovalProxyNotifications.class);
        var page = IntStream.range(0, FlowableApprovalProxyNotifications.BATCH_SIZE)
                .mapToObj(index -> new FlowableApprovalProxyNotifications.Candidate("tenant", UUID.randomUUID(), UUID.randomUUID(), "task-" + index)).toList();
        var last = page.get(page.size() - 1);
        var next = new FlowableApprovalProxyNotifications.Candidate("tenant", UUID.randomUUID(), UUID.randomUUID(), "next");
        doReturn(page, List.of()).when(notifications).candidates(any(), isNull());
        doThrow(new IllegalStateException("Synthetic scan failure")).doReturn(List.of(next)).when(notifications).candidates(any(), eq(last));
        doThrow(new IllegalStateException("Synthetic delivery failure")).when(notifications).pending(page.get(0));
        var scheduler = new ApprovalProxyNotificationScheduler(notifications);
        scheduler.poll(); scheduler.poll(); scheduler.poll(); scheduler.poll();
        verify(notifications).pending(last); verify(notifications).pending(next);
        verify(notifications, times(2)).candidates(any(), eq(last));
        verify(notifications, times(2)).candidates(any(), isNull());
    }
}
