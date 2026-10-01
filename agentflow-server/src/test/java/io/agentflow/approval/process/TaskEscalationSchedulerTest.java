package io.agentflow.approval.process;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 原名单不可投递、单笔错误与扫描错误都不能跳过其他待升级任务。
 * @author owlzhangfq@gmail.com
 */
class TaskEscalationSchedulerTest {
    @Test
    void keepsBoundedCursorAcrossFailuresAndReturnsToUndeliverableTasks() {
        var escalations = mock(FlowableTaskEscalations.class);
        var page = IntStream.range(0, FlowableTaskEscalations.BATCH_SIZE)
                .mapToObj(i -> new FlowableTaskEscalations.Candidate("task-" + i, Instant.EPOCH)).toList();
        var last = page.get(page.size() - 1);
        var next = new FlowableTaskEscalations.Candidate("next", Instant.EPOCH.plusSeconds(1));
        doReturn(page, List.of()).when(escalations).candidates(any(), isNull());
        doThrow(new IllegalStateException("Scan unavailable")).doReturn(List.of(next)).when(escalations).candidates(any(), eq(last));
        doThrow(new IllegalStateException("Delivery unavailable")).when(escalations).escalate(eq("task-0"), any());
        var scheduler = new TaskEscalationScheduler(escalations);
        scheduler.deliver(); scheduler.deliver(); scheduler.deliver(); scheduler.deliver();
        verify(escalations).escalate(eq(last.taskId()), any());
        verify(escalations).escalate(eq("next"), any());
        verify(escalations, times(2)).candidates(any(), eq(last));
        verify(escalations, times(2)).candidates(any(), isNull());
    }
}
