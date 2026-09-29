package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 按游标轮转补齐完成结算的原档案；不调用银行、ERP 或重新消费任何财务资源。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseArchiveWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExpenseArchiveWorker.class);
    private final JdbcExpenseArchiveRepository archives;
    private final ExpenseArchiveService service;
    private final ExpenseArchiveFiles files;
    private JdbcExpenseArchiveRepository.Candidate cursor;
    /** 无进程内完成标记，重启以已提交的数据库档案为准。 */
    public ExpenseArchiveWorker(JdbcExpenseArchiveRepository archives, ExpenseArchiveService service, ExpenseArchiveFiles files) {
        this.archives = archives; this.service = service; this.files = files;
    }
    /** 每批至多十单；一个缺件报销不会阻断下一单或持有长事务。 */
    public synchronized void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Archive worker must run outside a database transaction");
        var candidates = archives.candidates(cursor); if (candidates.isEmpty()) cursor = null;
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var manifest = service.prepare(candidate.tenantId(), candidate.reportId());
                if (manifest != null) { files.verify(manifest); service.complete(manifest); }
            } catch (DomainException blocked) {
                try { service.block(candidate.tenantId(), candidate.reportId(), blocked.code()); }
                catch (RuntimeException failed) { log(candidate, failed); }
            } catch (RuntimeException failed) { log(candidate, failed); }
            cursor = candidate;
        }
    }
    private static void log(JdbcExpenseArchiveRepository.Candidate candidate, RuntimeException failed) {
        LOG.error("Expense archive failed, errorCode={}, reportId={}", failed instanceof DomainException problem ? problem.code() : "ARCHIVE_FAILURE", candidate.reportId(), failed);
    }
}
