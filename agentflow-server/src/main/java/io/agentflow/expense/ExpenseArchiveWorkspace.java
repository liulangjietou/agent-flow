package io.agentflow.expense;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.common.DomainException;
import io.agentflow.finance.VoucherAccess;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 归档视图把原封存状态与当前争议分开，管理员也不能绕过当轮敏感字段。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseArchiveWorkspace {
    private final ExpenseSettlementAccess access;
    private final ApplicationFieldViews fields;
    private final JdbcExpenseArchiveRepository archives;
    private final JdbcExpenseSettlementRepository settlements;
    private final ExpenseArchiveSources sources;
    private final ExpenseArchiveFiles files;
    /** 沿用财务详情授权；完整包还须所有轮次字段原样可读，避免自由文本及历史正文旁路。 */
    public ExpenseArchiveWorkspace(ExpenseSettlementAccess access, ApplicationFieldViews fields, JdbcExpenseArchiveRepository archives,
            JdbcExpenseSettlementRepository settlements, ExpenseArchiveSources sources, ExpenseArchiveFiles files) {
        this.access = access; this.fields = fields; this.archives = archives; this.settlements = settlements; this.sources = sources; this.files = files;
    }
    /** 状态读取不触发文件 I/O，也不触发任何财务写入。 */
    public View get(UUID reportId, Map<String, String> parameters) {
        var context = context(reportId, parameters); var app = context.application();
        var entry = archives.find(app.tenantId(), reportId, context.roundNo()).orElse(null);
        if (entry != null && entry.archive() != null) {
            var archived = entry.archive(); String issue = null;
            try { sources.requireCurrent(archived.manifest().settlement()); }
            catch (DomainException changed) { issue = changed.code(); }
            return new View(reportId, app.id(), context.roundNo(), app.version(), context.businessVersion(), Status.ARCHIVED,
                    entry.sha256(), archived.archivedAt(), archived.manifest().originals().size(), archived.manifest().vouchers().size(), issue, true);
        }
        var current = settlements.find(app.tenantId(), reportId).filter(value -> value.input().source().roundNo() == context.roundNo()).orElse(null);
        String issue = current == null || current.status() != ExpenseSettlement.Status.SETTLED ? "ARCHIVE_SETTLEMENT_REQUIRED"
                : entry == null ? "ARCHIVE_CHECK_PENDING" : entry.issue();
        return new View(reportId, app.id(), context.roundNo(), app.version(), context.businessVersion(), entry == null ? Status.WAITING : Status.BLOCKED,
                null, null, 0, 0, issue, false);
    }
    /** 文件预检前后复核身份，返回保存的原清单而非按当前数据库临时拼出的档案。 */
    public JdbcExpenseArchiveRepository.Entry download(UUID reportId, Map<String, String> parameters) {
        var context = context(reportId, parameters);
        var entry = archives.find(context.application().tenantId(), reportId, context.roundNo()).filter(value -> value.archive() != null)
                .orElseThrow(() -> new DomainException("ARCHIVE_NOT_READY", "Expense archive is not sealed"));
        files.verify(entry.archive().manifest()); context(reportId, parameters); return entry;
    }
    private VoucherAccess.Context context(UUID reportId, Map<String, String> parameters) {
        if (!Set.of("roundNo").containsAll(parameters.keySet())) throw invalid(); Integer round = null;
        if (parameters.containsKey("roundNo")) {
            String value = parameters.get("roundNo"); if (!value.matches("[1-9][0-9]{0,8}")) throw invalid(); round = Integer.valueOf(value);
        }
        var context = access.read(reportId, round);
        if (fields.attachmentView(context.application(), context.roundNo()).restricted()) throw new DomainException("FORBIDDEN", "Complete original round field access is required for archive");
        return context;
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Archive query only accepts a positive roundNo"); }
    /**
     * 当前争议独立于不可变归档状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { WAITING, BLOCKED, ARCHIVED }
    /**
     * 最小投影不公开回单、账户、原命令、审批意见或文件名。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID reportId, UUID applicationId, int roundNo, long applicationVersion, long financialVersion,
                       Status status, String manifestSha256, Instant archivedAt, int originalCount, int voucherCount, String issue, boolean canDownload) { }
}
