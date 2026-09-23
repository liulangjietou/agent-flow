package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Semaphore;

/**
 * 编排一次有界摘要读取和文件生成，不持有数据库事务等待文件下载。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApplicationExportService {
    static final int MAX_RECORDS = 10_000;
    private final ApplicationSearchPort reader;
    private final Semaphore capacity = new Semaphore(1);

    /** 单实例同一时刻只生成一个工作簿，避免并发大文件耗尽审批服务内存。 */
    public ApplicationExportService(ApplicationSearchPort reader) { this.reader = reader; }

    /** 超限整次拒绝，不截断文件；查询或生成失败也释放容量。 */
    public byte[] export(Actor actor, ApplicationSearchPort.Query filters) {
        if (!capacity.tryAcquire()) throw new DomainException("APPLICATION_EXPORT_BUSY", "An application export is already running; retry later");
        try {
            Instant startedAt = Instant.now();
            var query = new ApplicationSearchPort.Query(filters.text(), filters.status(), filters.processKey(), filters.definitionVersion(),
                    filters.applicant(), filters.createdFrom(), filters.createdBefore(), MAX_RECORDS, null, null);
            var rows = reader.search(actor, query);
            if (rows.size() > MAX_RECORDS) throw new DomainException("APPLICATION_EXPORT_LIMIT_EXCEEDED", "Narrow the filters to at most 10000 records");
            return ApplicationWorkbook.write(rows, actor, query, startedAt);
        } catch (IOException exception) {
            throw new DomainException("APPLICATION_EXPORT_FAILED", "Unable to generate the application workbook; retry later");
        } finally { capacity.release(); }
    }
}
