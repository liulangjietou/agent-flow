package io.agentflow.expense;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 前页未知结果或单笔失败不能让后续单据永久无法到期处理。
 * @author owlzhangfq@gmail.com
 */
class ExpenseBudgetRetentionSchedulingTest {
    @Test void failedItemDoesNotStarveLaterPagesAndCursorWraps() {
        var repository = mock(JdbcExpenseBudgetRetentionRepository.class); var service = mock(ExpenseBudgetRetentionService.class);
        var page = IntStream.range(0, JdbcExpenseBudgetRetentionRepository.BATCH_SIZE).mapToObj(index ->
                new JdbcExpenseBudgetRetentionRepository.Candidate("demo", new UUID(0, index), 1)).toList();
        when(repository.candidates(any(Instant.class), isNull())).thenReturn(page);
        when(repository.candidates(any(Instant.class), eq(page.get(page.size() - 1)))).thenReturn(List.of());
        doThrow(new IllegalStateException("Synthetic failure")).when(service).process(eq(page.get(0)), any(Instant.class));
        var scheduler = new ExpenseBudgetRetentionScheduling(repository, service);
        scheduler.poll(); scheduler.poll(); scheduler.poll();
        verify(repository, times(2)).candidates(any(Instant.class), isNull());
        verify(repository).candidates(any(Instant.class), eq(page.get(page.size() - 1)));
        verify(service, times(2)).process(eq(page.get(page.size() - 1)), any(Instant.class));
    }
}
