package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * 用独立进程约束 PDFBox 的堆、递归和执行时长；父服务不解析不可信 PDF 对象。
 * @author owlzhangfq@gmail.com
 */
final class InvoicePdfInspector {
    private static final Logger log = LoggerFactory.getLogger(InvoicePdfInspector.class);
    private static final Semaphore SLOT = new Semaphore(1);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private final Path temporaryRoot;
    private final Duration timeout;

    InvoicePdfInspector() { this(Path.of(System.getProperty("java.io.tmpdir")), DEFAULT_TIMEOUT); }
    InvoicePdfInspector(Path temporaryRoot, Duration timeout) {
        this.temporaryRoot = temporaryRoot; this.timeout = timeout;
    }

    /** 只有完整检查成功才返回页数；失败、超时和中断均不产生可外发的部分结果。 */
    int pageCount(byte[] bytes) {
        if (!SLOT.tryAcquire()) throw new DomainException("INVOICE_EXTRACTION_SOURCE_BUSY", "Invoice PDF inspection is busy");
        Path directory = null;
        Process process = null;
        boolean interrupted = false;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            directory = Files.createTempDirectory(temporaryRoot, "agentflow-invoice-pdf-");
            Files.write(directory.resolve(InvoicePdfWorker.INPUT_FILE), bytes);
            var builder = new ProcessBuilder(command(directory));
            builder.directory(directory.toFile());
            // 不把数据库、模型密钥或部署环境传给只需读文件的子进程。
            builder.environment().clear();
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start(); process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw unavailable();
            Path result = directory.resolve(InvoicePdfWorker.RESULT_FILE);
            if (Files.isSymbolicLink(result) || Files.size(result) != InvoicePdfWorker.RESULT_BYTES) throw unavailable();
            try (var input = new DataInputStream(Files.newInputStream(result, LinkOption.NOFOLLOW_LINKS))) {
                if (input.readInt() != InvoicePdfWorker.RESULT_MAGIC) throw unavailable();
                int count = input.readInt();
                if (count < 1 || count > InvoiceExtractionInput.MAX_PAGES || input.read() != -1) throw unavailable();
                return count;
            }
        } catch (InterruptedException stopped) {
            interrupted = true; throw unavailable();
        } catch (IOException failed) { throw unavailable(); }
        finally {
            if (process != null && process.isAlive()) interrupted |= stop(process);
            if (process == null || !process.isAlive()) {
                if (directory != null) removeOwnedDirectory(directory);
                SLOT.release();
            } else {
                // 极端情况下停止失败，保留文件和占位，不再启动更多解析进程。
                log.error("PDF worker termination failed, errorCode={}, pid={}", "INVOICE_PDF_STOP_FAILED", process.pid());
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static List<String> command(Path directory) {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-Xss512k", "-XX:MaxDirectMemorySize=64m", "-XX:MaxMetaspaceSize=128m", "-XX:+ExitOnOutOfMemoryError",
                "-XX:-HeapDumpOnOutOfMemoryError", "-XX:ErrorFile=" + directory.resolve("jvm-error.log"),
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory,
                "-Dloader.main=" + InvoicePdfWorker.class.getName(), "-Dloader.home=" + directory, "-Dloader.path=",
                "-Dloader.config.name=agentflow-invoice-worker", "-cp", classpath));
        try {
            Class.forName(InvoicePdfWorker.class.getName(), false, ClassLoader.getSystemClassLoader());
            command.add(InvoicePdfWorker.class.getName());
        } catch (ClassNotFoundException nestedBootJar) {
            // 可执行 Spring Boot jar 的类在 BOOT-INF/classes，使用其内置启动器加载相同安装包。
            command.add(BOOT_LAUNCHER);
        }
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
            log.error("PDF preparation cleanup failed, errorCode={}, directory={}", "INVOICE_PDF_CLEANUP_FAILED", directory, failed);
        }
    }

    private static DomainException unavailable() {
        return new DomainException("INVOICE_EXTRACTION_SOURCE_UNAVAILABLE", "Invoice PDF inspection failed or exceeded its limits");
    }
}
