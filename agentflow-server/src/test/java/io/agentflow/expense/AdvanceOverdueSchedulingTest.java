package io.agentflow.expense;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 一页中失败的借款不阻止后续提醒，下一轮扫描仍能重试失败项。
 * @author owlzhangfq@gmail.com
 */
class AdvanceOverdueSchedulingTest {
    @Test void failedItemDoesNotStarveLaterPagesAndCursorWraps() {
        var repository = mock(JdbcAdvanceOverdueRepository.class); var reminders = mock(AdvanceOverdueReminders.class);
        var page = IntStream.range(0, JdbcAdvanceOverdueRepository.BATCH_SIZE).mapToObj(index ->
                new JdbcAdvanceOverdueRepository.Candidate("demo", new UUID(0, index), LocalDate.parse("2026-10-01"))).toList();
        when(repository.candidates(any(Instant.class), isNull())).thenReturn(page);
        when(repository.candidates(any(Instant.class), eq(page.get(page.size() - 1)))).thenReturn(List.of());
        when(reminders.remind(eq(page.get(0)), any(Instant.class))).thenThrow(new IllegalStateException("Synthetic failure"));
        var scheduling = new AdvanceOverdueScheduling(repository, reminders);
        scheduling.poll(); scheduling.poll(); scheduling.poll();
        verify(repository, times(2)).candidates(any(Instant.class), isNull());
        verify(repository).candidates(any(Instant.class), eq(page.get(page.size() - 1)));
        verify(reminders, times(2)).remind(eq(page.get(page.size() - 1)), any(Instant.class));
    }
}
