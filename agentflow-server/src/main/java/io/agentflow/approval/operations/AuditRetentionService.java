package io.agentflow.approval.operations;

import io.agentflow.approval.operations.mapper.AuditRetentionMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.SystemRole;
import io.agentflow.mybatis.SqlRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 审计事件分批留存归档治理服务：每批使用独立短事务将超期热表审计原子搬迁至 audit_event_archive，
 * 既避免大事务锁表，又不销毁原始审计事实。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AuditRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRetentionService.class);
    static final int MIN_RETENTION_DAYS = 30;
    static final int MAX_RETENTION_DAYS = 3650;
    static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 200;

    private final AuditRetentionMapper mapper;
    private final TransactionTemplate transaction;
    private final boolean retentionEnabled;
    private final int defaultRetentionDays;
    private final Counter archivedCounter;

    /** 构造审计留存服务；定时归档默认关闭，生产按需显式开启。 */
    public AuditRetentionService(
            AuditRetentionMapper mapper,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meters,
            @Value("${agentflow.audit.retention-enabled:false}") boolean retentionEnabled,
            @Value("${agentflow.audit.retention-days:365}") int defaultRetentionDays) {
        if (defaultRetentionDays < MIN_RETENTION_DAYS || defaultRetentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("agentflow.audit.retention-days must be between 30 and 3650");
        }
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.retentionEnabled = retentionEnabled;
        this.defaultRetentionDays = defaultRetentionDays;
        MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
        this.archivedCounter = registry == null ? null
                : Counter.builder("agentflow.audit.retention.archived")
                        .description("Total audit_event rows moved to audit_event_archive")
                        .register(registry);
    }

    /** 定时分批归档超期审计事件（仅在 agentflow.audit.retention-enabled=true 时执行）。 */
    @Scheduled(cron = "${agentflow.audit.retention-cron:0 30 3 * * *}")
    public void scheduledArchive() {
        if (!retentionEnabled) return;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(Duration.ofDays(defaultRetentionDays));
        int moved = archiveBatches(null, cutoff, now);
        if (moved > 0) {
            LOG.info("Scheduled audit retention archive completed, archivedCount={}, cutoff={}", moved, cutoff);
        }
    }

    /** 管理员查看本租户审计留存配置、已归档总量及最近执行台账。 */
    public RetentionStatus status(Actor admin) {
        admin.requireRole(SystemRole.ADMIN);
        var countRows = mapper.countArchived(admin.tenantId());
        long archivedTotal = countRows.isEmpty() ? 0L : countRows.get(0).getLong("total_count");
        List<RunRecord> runs = mapper.listRuns(admin.tenantId(), 20).stream()
                .map(row -> new RunRecord(
                        UUID.fromString(row.getString("id")),
                        row.getTimestamp("cutoff_at").toInstant(),
                        row.getInt("archived_count"),
                        row.getString("triggered_by"),
                        row.getTimestamp("executed_at").toInstant()))
                .toList();
        return new RetentionStatus(retentionEnabled, defaultRetentionDays, archivedTotal, runs);
    }

    /** 管理员在本租户范围手动执行一次分批归档，不允许指定少于 30 天的保留期。 */
    public RunRecord runForTenant(Actor admin, Integer retentionDaysOverride) {
        admin.requireRole(SystemRole.ADMIN);
        int days = retentionDaysOverride == null ? defaultRetentionDays : retentionDaysOverride;
        if (days < MIN_RETENTION_DAYS || days > MAX_RETENTION_DAYS) {
            throw new DomainException(ErrorCode.INVALID_AUDIT_QUERY,
                    "retentionDays must be between 30 and 3650");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(Duration.ofDays(days));
        int moved = archiveBatches(admin.tenantId(), cutoff, now);
        UUID runId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> mapper.insertRun(
                runId.toString(),
                admin.tenantId(),
                Timestamp.from(cutoff),
                moved,
                admin.userId(),
                Timestamp.from(now)));
        LOG.info("Tenant audit retention archive completed, tenantId={}, archivedCount={}, retentionDays={}",
                admin.tenantId(), moved, days);
        return new RunRecord(runId, cutoff, moved, admin.userId(), now);
    }

    /** 分批短事务将早于 cutoff 的审计记录从 audit_event 搬迁至 audit_event_archive。 */
    public int archiveBatches(String tenantId, Instant cutoff, Instant archivedAt) {
        int total = 0;
        Timestamp cutoffTs = Timestamp.from(cutoff);
        Timestamp archivedTs = Timestamp.from(archivedAt);
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            Integer moved = transaction.execute(status -> {
                List<SqlRow> rows = mapper.findExpiredBatch(tenantId, cutoffTs, BATCH_SIZE);
                if (rows.isEmpty()) return 0;
                int count = 0;
                for (SqlRow row : rows) {
                    String id = row.getString("id");
                    String rowTenant = row.getString("tenant_id");
                    mapper.insertArchive(
                            id,
                            rowTenant,
                            row.getString("event_id"),
                            row.getString("aggregate_type"),
                            row.getString("aggregate_id"),
                            row.getLong("aggregate_version"),
                            row.getString("application_id"),
                            row.getString("action"),
                            row.getString("actor_id"),
                            row.getString("payload_json"),
                            row.getTimestamp("occurred_at"),
                            archivedTs);
                    count += mapper.deleteFromHotTable(rowTenant, id);
                }
                return count;
            });
            int batchMoved = moved == null ? 0 : moved;
            total += batchMoved;
            if (archivedCounter != null && batchMoved > 0) {
                archivedCounter.increment(batchMoved);
            }
            if (batchMoved < BATCH_SIZE) break;
        }
        return total;
    }

    /**
     * 租户留存归档状态概览。
     * @author owlzhangfq@gmail.com
     */
    public record RetentionStatus(
            boolean scheduledEnabled,
            int defaultRetentionDays,
            long archivedTotal,
            List<RunRecord> recentRuns) { }

    /**
     * 单次留存归档执行台账。
     * @author owlzhangfq@gmail.com
     */
    public record RunRecord(
            UUID runId,
            Instant cutoffAt,
            int archivedCount,
            String triggeredBy,
            Instant executedAt) { }
}
