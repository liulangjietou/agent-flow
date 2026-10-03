package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 使用实际子进程证明超时与线程中断会结束本次工作，并清理自己创建的临时原件。
 * @author owlzhangfq@gmail.com
 */
class InvoicePdfInspectorTest {
    private final Path directory = Path.of("/fyoung/tmp/agentflow-pdf-process-test-" + UUID.randomUUID());

    @AfterEach
    void removeEmptyFixtureRoot() throws Exception {
        Thread.interrupted();
        if (Files.exists(directory)) {
            try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
            Files.delete(directory);
        }
    }

    @Test
    void successfulAndRejectedInspectionBothRemoveOnlyTheirPrivateDirectories() throws Exception {
        Files.createDirectory(directory);
        var inspector = new InvoicePdfInspector(directory, Duration.ofSeconds(15));
        byte[] bytes = InvoicePdfSourceTest.pdf(2, doc -> { });
        assertThat(inspector.pageCount(bytes)).isEqualTo(2);
        assertThatThrownBy(() -> inspector.pageCount(new byte[]{1, 2, 3})).isInstanceOf(DomainException.class);
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void timeoutKillsTheOwnedWorkerAndReleasesItsSlotForTheNextDocument() throws Exception {
        Files.createDirectory(directory);
        byte[] bytes = InvoicePdfSourceTest.pdf(1, doc -> { });
        var childrenBefore = ProcessHandle.current().children().map(ProcessHandle::pid).toList();
        var inspector = new InvoicePdfInspector(directory, Duration.ofMillis(1));
        assertThatThrownBy(() -> inspector.pageCount(bytes)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
        assertThat(ProcessHandle.current().children().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList())
                .isSubsetOf(childrenBefore);
        assertThat(new InvoicePdfInspector(directory, Duration.ofSeconds(15)).pageCount(bytes)).isOne();
    }

    @Test
    void anAlreadyInterruptedCallerDoesNotStartAWorkerOrLoseTheInterrupt() throws Exception {
        Files.createDirectory(directory);
        var inspector = new InvoicePdfInspector(directory, Duration.ofSeconds(15));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> inspector.pageCount(new byte[]{1})).isInstanceOf(DomainException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }
}
