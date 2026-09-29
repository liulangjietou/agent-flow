package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 先读取候选、事务外核验文件、再锁后封存，长时间文件读取不持有业务锁。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseArchiveService {
    private final ExpenseReportRepository reports;
    private final JdbcExpenseSettlementRepository settlements;
    private final JdbcExpenseArchiveRepository archives;
    private final ExpenseArchiveSources sources;
    /** 短事务只拥有本地快照及审计的原子性。 */
    public ExpenseArchiveService(ExpenseReportRepository reports, JdbcExpenseSettlementRepository settlements,
            JdbcExpenseArchiveRepository archives, ExpenseArchiveSources sources) {
        this.reports = reports; this.settlements = settlements; this.archives = archives; this.sources = sources;
    }
    /** 重启或多进程重复扫描时跳过已经封存的同轮档案。 */
    @Transactional
    public ExpenseArchive.Manifest prepare(String tenant, UUID reportId) {
        reports.lock(tenant, reportId); var settlement = settlements.find(tenant, reportId).orElseThrow();
        if (sealed(settlement)) return null;
        return sources.capture(tenant, reportId);
    }
    /** 第二次完整读取必须等于文件校验前的快照，并发查询或退票使本次候选失效。 */
    @Transactional
    public void complete(ExpenseArchive.Manifest expected) {
        var source = expected.source(); reports.lock(source.tenantId(), source.businessId());
        var settlement = settlements.find(source.tenantId(), source.businessId()).orElseThrow();
        if (sealed(settlement)) return;
        var current = sources.capture(source.tenantId(), source.businessId());
        if (!current.equals(expected)) throw new DomainException("ARCHIVE_SOURCE_CHANGED", "Archive evidence changed while checking original files");
        archives.seal(ExpenseArchive.seal(current, now()));
    }
    /** 失败的候选只能留下原因，不能覆盖另一执行者已经完成的档案。 */
    @Transactional
    public void block(String tenant, UUID reportId, String issue) {
        reports.lock(tenant, reportId); settlements.find(tenant, reportId).ifPresent(value -> archives.blocked(value, issue, now()));
    }
    private boolean sealed(ExpenseSettlement value) {
        var source = value.input().source();
        return archives.find(source.tenantId(), source.businessId(), source.roundNo()).map(entry -> entry.archive() != null).orElse(false);
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
