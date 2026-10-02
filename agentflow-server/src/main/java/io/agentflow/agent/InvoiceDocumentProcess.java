package io.agentflow.agent;

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
    private static final Semaphore SLOT = new Semaphore(1);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private final Path temporaryRoot;
    private final Duration timeout;

    InvoiceDocumentProcess(Path temporaryRoot, Duration timeout) {
        this.temporaryRoot = temporaryRoot; this.timeout = timeout;
    }

    /** 只有子进程正常结束且完整结果通过校验才返回；停止失败时保留占位，防止进程继续累积。 */
    <T> T execute(Program program, byte[] original, List<String> arguments, ResultReader<T> reader) {
        if (original.length < 1 || original.length > InvoiceVerificationPort.Request.MAX_ORIGINAL_BYTES) throw unavailable();
        if (!SLOT.tryAcquire()) throw new DomainException("INVOICE_EXTRACTION_SOURCE_BUSY", "Invoice document preparation is busy");
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
                SLOT.release();
            } else {
                log.error("Document worker termination failed, errorCode={}, worker={}, pid={}",
                        "INVOICE_DOCUMENT_STOP_FAILED", program.main().getSimpleName(), process.pid());
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
        return new DomainException("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE", "Invoice document preparation failed or exceeded its limits");
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
