package io.agentflow.agent;

import io.agentflow.common.ErrorCode;
import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceVerificationPort;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF 检查与 OFD 渲染共用进程槽、启动参数和清理；不在父进程解析原件。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceDocumentProcess {
    private static final Logger log = LoggerFactory.getLogger(InvoiceDocumentProcess.class);
    private static final int DEFAULT_SLOTS = 2;
    private static final int MAX_SLOTS = 8;
    private static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(3);
    private static final Semaphore SHARED_SLOTS = new Semaphore(DEFAULT_SLOTS, true);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private final Path temporaryRoot;
    private final Duration timeout;
    private final Semaphore slots;
    private final Duration acquireTimeout;

    InvoiceDocumentProcess(Path temporaryRoot, Duration timeout) {
        this(temporaryRoot, timeout, SHARED_SLOTS, DEFAULT_ACQUIRE_TIMEOUT);
    }

    InvoiceDocumentProcess(Path temporaryRoot, Duration timeout, int concurrency, Duration acquireTimeout) {
        this(temporaryRoot, timeout, new Semaphore(Math.max(1, Math.min(concurrency, MAX_SLOTS)), true), acquireTimeout);
    }

    private InvoiceDocumentProcess(Path temporaryRoot, Duration timeout, Semaphore slots, Duration acquireTimeout) {
        this.temporaryRoot = temporaryRoot;
        this.timeout = timeout;
        this.slots = slots;
        this.acquireTimeout = acquireTimeout == null || acquireTimeout.isNegative() ? Duration.ZERO : acquireTimeout;
    }

    /** 只有子进程正常结束且完整结果通过校验才返回；超时强杀未立即退出时在操作系统回收后异步释放槽位，避免永久泄漏。 */
    <T> T execute(Program program, byte[] original, List<String> arguments, ResultReader<T> reader) {
        if (original.length < 1 || original.length > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES) throw unavailable();
        if (Thread.currentThread().isInterrupted()) throw unavailable();
        boolean acquired;
        try {
            acquired = acquireTimeout.isZero() ? slots.tryAcquire() : slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (!acquired) throw new DomainException(ErrorCode.INVOICE_EXTRACTION_SOURCE_BUSY, "Invoice document preparation is busy");
        Path directory = null;
        Process process = null;
        boolean interrupted = false;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            directory = Files.createTempDirectory(temporaryRoot, "agentflow-invoice-");
            Files.write(directory.resolve(program.inputFile()), original);
            var builder = new ProcessBuilder(command(program, arguments, directory));
            builder.directory(directory.toFile());
            // 子进程只通过明确参数和私有目录取得输入，不继承数据库、模型密钥或 JVM 环境选项。
            builder.environment().clear();
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start(); process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw unavailable();
            return reader.read(directory);
        } catch (InterruptedException stopped) {
            interrupted = true; throw unavailable();
        } catch (IOException failed) { throw unavailable(); }
        finally {
            if (process != null && process.isAlive()) interrupted |= stop(process);
            if (process == null || !process.isAlive()) {
                if (directory != null) removeOwnedDirectory(directory);
                slots.release();
            } else {
                Path ownedDirectory = directory;
                Process stuck = process;
                log.error("Document worker termination delayed, errorCode={}, worker={}, pid={}",
                        "INVOICE_DOCUMENT_STOP_FAILED", program.main().getSimpleName(), stuck.pid());
                // 注册进程退出回调：待内核回收后自动清理临时目录并归还槽位，防止实例永久耗尽许可。
                stuck.onExit().whenComplete((exited, error) -> {
                    if (ownedDirectory != null) removeOwnedDirectory(ownedDirectory);
                    slots.release();
                    log.info("Delayed document worker exited and slot released, errorCode={}, worker={}, pid={}",
                            "NONE", program.main().getSimpleName(), stuck.pid());
                });
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static List<String> command(Program program, List<String> arguments, Path directory) {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + program.heapMegabytes() + "m", "-Xss512k", "-XX:MaxDirectMemorySize=64m", "-XX:MaxMetaspaceSize=128m",
                "-XX:+ExitOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:ErrorFile=" + directory.resolve("jvm-error.log"),
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory,
                "-Dloader.main=" + program.main().getName(), "-Dloader.home=" + directory, "-Dloader.path=",
                "-Dloader.config.name=agentflow-invoice-worker", "-cp", classpath));
        try {
            Class.forName(program.main().getName(), false, ClassLoader.getSystemClassLoader());
            command.add(program.main().getName());
        } catch (ClassNotFoundException nestedBootJar) {
            // 安装包的工作类位于 BOOT-INF/classes，交给同一安装包中的启动器加载。
            command.add(BOOT_LAUNCHER);
        }
        command.addAll(arguments);
        return command;
    }

    private static boolean stop(Process process) {
        boolean interrupted = Thread.interrupted();
        process.destroyForcibly();
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException again) { interrupted = true; }
        }
        return interrupted;
    }

    private static void removeOwnedDirectory(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failed) {
            log.error("Document preparation cleanup failed, errorCode={}, directory={}", "INVOICE_DOCUMENT_CLEANUP_FAILED", directory, failed);
        }
    }

    private static DomainException unavailable() {
        return new DomainException(ErrorCode.INVOICE_EXTRACTION_SOURCE_UNAVAILABLE, "Invoice document preparation failed or exceeded its limits");
    }

    /**
     * 各适配器提供固定工作入口、文件名和堆额度，不接受原件中的启动配置。
     * @author owlzhangfq@gmail.com
     */
    record Program(Class<?> main, String inputFile, int heapMegabytes) { }

    /**
     * 结果的完整性和资源限额属于各文件协议；生命周期始终由执行器统一管理。
     * @author owlzhangfq@gmail.com
     */
    @FunctionalInterface
    interface ResultReader<T> { T read(Path directory) throws IOException; }
}
