package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceVerificationPort;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实 JVM 验证跨格式并发、工作目录和环境隔离，以及失败后的资源接续。
 * @author owlzhangfq@gmail.com
 */
class InvoiceDocumentProcessTest {
    private static final InvoiceDocumentProcess.Program PROGRAM = new InvoiceDocumentProcess.Program(ProbeWorker.class, "input.bin", 64);
    @TempDir Path directory;

    @Test
    void workerHasItsOwnPidBoundedHeapNoDeploymentEnvironmentAndPrivateHome() {
        Observation observation = execute("success", InvoiceDocumentProcessTest::read);
        assertThat(observation.pid()).isNotEqualTo(ProcessHandle.current().pid());
        assertThat(ProcessHandle.of(observation.pid()).filter(ProcessHandle::isAlive)).isEmpty();
        assertThat(observation.heap()).isLessThanOrEqualTo(64L * 1024 * 1024);
        // OpenJDK 的 ProcessBuilder/Basic.java 同样排除 macOS 启动时自行生成的这两个字段。
        if (System.getProperty("os.name").startsWith("Mac")) {
            assertThat(observation.environment()).allSatisfy(name -> assertThat(name)
                    .isIn("__CF_USER_TEXT_ENCODING", "JAVA_MAIN_CLASS_" + observation.pid()));
        } else assertThat(observation.environment()).isEmpty();
        assertThat(observation.home()).isEqualTo(observation.temporary());
        assertThat(Path.of(observation.home()).getParent().toAbsolutePath().normalize()).isEqualTo(directory.toAbsolutePath().normalize());
        assertThat(observation.input()).containsExactly(1, 2, 3);
        assertThat(Files.exists(Path.of(observation.home()))).isFalse();
    }

    @Test
    void oneRunningDocumentBlocksBothPdfAndOfdUntilItsResultIsConsumed() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> execute("wait", InvoiceDocumentProcessTest::read));
        try {
            Path ready = awaitReady();
            assertBusy(() -> new InvoicePdfInspector(directory, Duration.ofSeconds(15)).pageCount(new byte[]{1}));
            assertBusy(() -> new InvoiceOfdInspector(directory, Duration.ofSeconds(30)).render(new byte[]{1}, null));
            Files.writeString(ready.getParent().resolve("release"), "continue");
            assertThat(future.get(10, TimeUnit.SECONDS).input()).containsExactly(1, 2, 3);
            assertThat(execute("success", InvoiceDocumentProcessTest::read).input()).containsExactly(1, 2, 3);
        } finally {
            future.cancel(true); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test
    void interruptionDuringExecutionKillsTheWorkerAndRestoresTheCallerInterrupt() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var finished = new CountDownLatch(1); var restored = new AtomicBoolean();
        var future = executor.submit(() -> {
            try { execute("wait", InvoiceDocumentProcessTest::read); }
            catch (DomainException failed) { restored.set(Thread.currentThread().isInterrupted()); }
            finally { finished.countDown(); }
        });
        try {
            long pid = Long.parseLong(Files.readString(awaitReady()));
            assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isPresent();
            future.cancel(true);
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(restored).isTrue();
            assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
            assertThat(execute("success", InvoiceDocumentProcessTest::read).input()).containsExactly(1, 2, 3);
        } finally {
            future.cancel(true); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test
    void nonzeroExitAndResultReadFailureNeverReturnPartialStateAndReleaseTheSlot() throws Exception {
        var called = new AtomicBoolean();
        assertUnavailable(() -> execute("reject", dir -> { called.set(true); return read(dir); }));
        assertThat(called).isFalse();
        assertUnavailable(() -> execute("success", dir -> { throw new IOException("Synthetic result failure"); }));
        assertThat(execute("success", InvoiceDocumentProcessTest::read).input()).containsExactly(1, 2, 3);
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test
    void oversizedInputAndDirectoryCreationFailureDoNotHoldTheGlobalSlot() {
        var process = new InvoiceDocumentProcess(directory, Duration.ofSeconds(15));
        assertUnavailable(() -> process.execute(PROGRAM, new byte[0], List.of("success"), InvoiceDocumentProcessTest::read));
        assertUnavailable(() -> process.execute(PROGRAM, new byte[InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES + 1], List.of("success"), InvoiceDocumentProcessTest::read));
        var unavailableRoot = new InvoiceDocumentProcess(directory.resolve("missing"), Duration.ofSeconds(15));
        assertUnavailable(() -> unavailableRoot.execute(PROGRAM, new byte[]{1}, List.of("success"), InvoiceDocumentProcessTest::read));
        assertThat(execute("success", InvoiceDocumentProcessTest::read).input()).containsExactly(1, 2, 3);
    }

    private Observation execute(String mode, InvoiceDocumentProcess.ResultReader<Observation> reader) {
        return new InvoiceDocumentProcess(directory, Duration.ofSeconds(20)).execute(PROGRAM, new byte[]{1, 2, 3}, List.of(mode), reader);
    }

    private Path awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var files = Files.walk(directory)) {
                var ready = files.filter(path -> path.getFileName().toString().equals("ready.pid")).findFirst();
                if (ready.isPresent() && Files.size(ready.get()) > 0) return ready.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Worker did not reach its wait point");
    }

    private static Observation read(Path directory) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(directory.resolve("state.bin")))) {
            long pid = input.readLong(), heap = input.readLong();
            int count = input.readInt(); var environment = new ArrayList<String>();
            for (int i = 0; i < count; i++) environment.add(input.readUTF());
            return new Observation(pid, heap, List.copyOf(environment), input.readUTF(), input.readUTF(), input.readAllBytes());
        }
    }

    private static void assertBusy(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_BUSY"));
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE"));
    }

    /**
     * 只记录进程边界事实，不读取或输出环境变量的内容。
     * @author owlzhangfq@gmail.com
     */
    private record Observation(long pid, long heap, List<String> environment, String home, String temporary, byte[] input) { }

    /**
     * 测试专用进程，不进入生产安装包；通过明确等待点验证并发与中断。
     * @author owlzhangfq@gmail.com
     */
    public static final class ProbeWorker {
        /** 写出有界事实后按测试指令正常结束、失败或等待释放。 */
        public static void main(String[] args) throws Exception {
            try (var output = new DataOutputStream(Files.newOutputStream(Path.of("state.bin")))) {
                output.writeLong(ProcessHandle.current().pid()); output.writeLong(Runtime.getRuntime().maxMemory());
                output.writeInt(System.getenv().size());
                for (String name : new java.util.TreeSet<>(System.getenv().keySet())) output.writeUTF(name);
                output.writeUTF(System.getProperty("user.home"));
                output.writeUTF(System.getProperty("java.io.tmpdir")); output.write(Files.readAllBytes(Path.of("input.bin")));
            }
            if (args[0].equals("reject")) System.exit(1);
            if (args[0].equals("wait")) {
                Files.writeString(Path.of("ready.pid"), Long.toString(ProcessHandle.current().pid()));
                while (!Files.exists(Path.of("release"))) Thread.sleep(10);
            }
        }
    }
}
