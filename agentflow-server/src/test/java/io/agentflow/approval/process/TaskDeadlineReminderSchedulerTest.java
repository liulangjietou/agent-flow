package io.agentflow.approval.process;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

/**
 * 验证无接收人的整页任务不会饿死下一页，扫完后会重新检查此前未投递任务。
 * @author owlzhangfq@gmail.com
 */
class TaskDeadlineReminderSchedulerTest {
    @Test
    void advancesPastUndeliverablePageThenRestartsAfterTheQueueEnds() {
        var reminders = mock(FlowableTaskDeadlineReminders.class);
        var firstPage = IntStream.range(0, FlowableTaskDeadlineReminders.BATCH_SIZE)
                .mapToObj(index -> new FlowableTaskDeadlineReminders.Candidate("task-" + index, Instant.EPOCH)).toList();
        var last = firstPage.get(firstPage.size() - 1);
        var next = new FlowableTaskDeadlineReminders.Candidate("next", Instant.EPOCH.plusSeconds(1));
        doReturn(firstPage, List.of()).when(reminders).candidates(any(), isNull());
        doReturn(List.of(next)).when(reminders).candidates(any(), eq(last));
        var scheduler = new TaskDeadlineReminderScheduler(reminders);
        scheduler.deliver();
        scheduler.deliver();
        verify(reminders).remind(eq("next"), any());
        scheduler.deliver();
        verify(reminders, org.mockito.Mockito.times(2)).candidates(any(), isNull());
    }
}
