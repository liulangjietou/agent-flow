package io.agentflow.expense;

import io.agentflow.storage.LocalDocumentStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 档案包引用原字节，逐文件校验并流式输出，不把全部文件或 ZIP 同时放入内存。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseArchiveFiles {
    private final LocalDocumentStore documents;
    /** 沿用现有不可覆盖的原件目录和数据库/文件配套恢复。 */
    public ExpenseArchiveFiles(LocalDocumentStore documents) { this.documents = documents; }
    /** 缺失或损坏的原件不能成为成功归档或有效下载；没有文件时不依赖存储配置。 */
    public void verify(ExpenseArchive.Manifest manifest) {
        requireOutsideTransaction();
        for (var original : manifest.originals()) documents.read(content(original));
    }
    /** 清单使用原存字节；ZIP 内路径由服务端生成，清单摘要单独附带便于离线核验。 */
    public void write(JdbcExpenseArchiveRepository.Entry entry, OutputStream output) throws IOException {
        requireOutsideTransaction();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            add(zip, "manifest.json", entry.encoded().getBytes(StandardCharsets.UTF_8));
            add(zip, "manifest.sha256", (entry.sha256() + "  manifest.json\n").getBytes(StandardCharsets.UTF_8));
            for (var original : entry.archive().manifest().originals()) add(zip, original.entryName(), documents.read(content(original)));
        }
    }
    private static void add(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        var entry = new ZipEntry(path); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    private static LocalDocumentStore.Content content(ExpenseArchive.Original original) {
        var file = original.file(); return new LocalDocumentStore.Content(file.id(), file.size(), file.sha256());
    }
    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Archive file access must run outside a database transaction");
    }
}
