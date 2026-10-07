package io.agentflow.expense;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前票号占用的授权读模型；只读原互斥键，不将可撤销的单据权限固化到预检历史。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InvoiceOccupationQueries {
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActor actors;
    private final InvoiceRepository invoices;
    private final ExpenseReportRepository reports;
    private final ExpenseDraftService expenses;

    /** 单号可见性复用报销详情的当前轮次及敏感字段权限，管理员没有额外豁免。 */
    public InvoiceOccupationQueries(JdbcTemplate jdbc, CurrentActor actors, InvoiceRepository invoices,
            ExpenseReportRepository reports, ExpenseDraftService expenses) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.actors = actors;
        this.invoices = invoices; this.reports = reports; this.expenses = expenses;
    }

    /** 批量读取本人票夹中已知票号的有效占用；另一份上传原件仍指向同一权威互斥键。 */
    public Map<UUID, View> wallet(Collection<Invoice> candidates) {
        var owned = owned(candidates); var claims = claims(owned); var projected = new LinkedHashMap<String, View>();
        var result = new LinkedHashMap<UUID, View>();
        for (var invoice : owned) {
            String key = invoice.facts().key().canonical(); var claim = claims.get(key);
            if (claim != null) result.put(invoice.id(), projected.computeIfAbsent(key, ignored -> view(claim)));
        }
        return Map.copyOf(result);
    }

    /** 提交已经回滚后只读本人当前草稿；调用者不能借冲突详情探测其他报销单。 */
    public List<Conflict> conflicts(UUID reportId) {
        var actor = actors.actor();
        var report = reports.find(actor.tenantId(), reportId).filter(value -> actor.userId().equals(value.employeeId()))
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Expense report not found"));
        return conflicts(report);
    }

    /** 只返回当前内容的冲突行；本单原轮次预留的迁移和替换仍由原提交规则复核。 */
    public List<Conflict> conflicts(ExpenseReport report) {
        var actor = actors.actor();
        if (!actor.tenantId().equals(report.tenantId()) || !actor.userId().equals(report.employeeId())) {
            throw new DomainException("NOT_FOUND", "Expense report not found");
        }
        var ids = report.content().lines().stream().flatMap(line -> line.invoiceIds().stream()).distinct().toList();
        if (ids.isEmpty()) return List.of();
        var candidates = owned(invoices.findAll(actor.tenantId(), ids).values()); var claims = claims(candidates);
        var byId = new LinkedHashMap<UUID, Claim>();
        for (var invoice : candidates) {
            var claim = claims.get(invoice.facts().key().canonical());
            if (claim != null && !(claim.status() == Invoice.Occupation.OCCUPIED
                    && report.id().equals(claim.reportId()))) byId.put(invoice.id(), claim);
        }
        var projected = new LinkedHashMap<Claim, View>();
        return report.content().lines().stream().flatMap(line -> line.invoiceIds().stream().filter(byId::containsKey)
                .map(id -> new Conflict(line.lineNo(), id, projected.computeIfAbsent(byId.get(id), this::view)))).toList();
    }

    private List<Invoice> owned(Collection<Invoice> candidates) {
        var actor = actors.actor();
        return candidates.stream().filter(invoice -> invoice.tenantId().equals(actor.tenantId())
                && invoice.ownerId().equals(actor.userId()) && invoice.facts() != null).toList();
    }

    private Map<String, Claim> claims(List<Invoice> candidates) {
        if (candidates.isEmpty()) return Map.of();
        var keys = candidates.stream().map(invoice -> invoice.facts().key().canonical()).distinct().toList();
        var result = new LinkedHashMap<String, Claim>();
        jdbc.query("""
                SELECT invoice_key,report_id,round_no,line_no,status FROM invoice_active_claim
                WHERE tenant_id=:tenant AND invoice_key IN (:keys)
                """, Map.of("tenant", actors.actor().tenantId(), "keys", keys), row -> {
            result.put(row.getString("invoice_key"), new Claim(uuid(row.getString("report_id")),
                    row.getInt("round_no"), row.getInt("line_no"), Invoice.Occupation.valueOf(row.getString("status"))));
        });
        return result;
    }

    private View view(Claim claim) {
        ExpenseReference reference = null;
        if (claim.reportId() != null) {
            try {
                var report = expenses.read(claim.reportId(), claim.roundNo());
                reference = new ExpenseReference(report.id(), report.applicationId(), report.businessNo(), claim.roundNo(), claim.lineNo());
            } catch (DomainException denied) {
                if (!List.of("NOT_FOUND", "FORBIDDEN").contains(denied.code())) throw denied;
                // 无权读取或历史来源已缺失时只保留占用事实，不返回标识、单号和人员信息。
            }
        }
        return new View(claim.status(), reference);
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }

    /**
     * 原互斥表的最小行，采购来源没有报销单标识。
     * @author owlzhangfq@gmail.com
     */
    private record Claim(UUID reportId, int roundNo, int lineNo, Invoice.Occupation status) { }
    /**
     * 当前有效占用；没有 expense 不代表未占用，只表示不能展示报销来源。
     * @author owlzhangfq@gmail.com
     */
    public record View(Invoice.Occupation status, ExpenseReference expense) { }
    /**
     * 通过原轮次详情权限后才能返回的最小业务定位信息。
     * @author owlzhangfq@gmail.com
     */
    public record ExpenseReference(UUID reportId, UUID applicationId, String businessNo, int roundNo, int lineNo) { }
    /**
     * 本人当前费用行与票号占用的关联，不回显占用方票据、员工或资金信息。
     * @author owlzhangfq@gmail.com
     */
    public record Conflict(int lineNo, UUID invoiceId, View occupation) { }
}
