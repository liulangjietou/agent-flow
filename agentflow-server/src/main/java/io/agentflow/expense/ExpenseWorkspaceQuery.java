package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本人报销与可引用资金的轻量读模型；不向管理员提供全员余额，也不返回其他单据占用明细。
 * @author owlzhangfq@gmail.com
 */
@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ExpenseWorkspaceQuery {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final JdbcTemplate jdbc;
    private final ExpenseRequestRepository requests;
    private final EmployeeAdvanceRepository advances;

    /** 目录先按独立租户和本人列限制，余额仍经已有仓储恢复与核对。 */
    public ExpenseWorkspaceQuery(CurrentActor actors, JdbcTemplate jdbc, ExpenseRequestRepository requests, EmployeeAdvanceRepository advances) {
        this.actors = actors; this.jdbc = jdbc; this.requests = requests; this.advances = advances;
    }

    /** 草稿和已提交报销共用一份本人列表，不把普通表单当成费用单据。 */
    public ReportPage reports(Map<String, String> parameters) {
        var query = page(parameters, Set.of("limit", "beforeId", "status")); var actor = actors.actor();
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId()));
        String filter = "";
        if (parameters.containsKey("status")) {
            try { ApplicationStatus.valueOf(parameters.get("status")); } catch (IllegalArgumentException invalid) { throw invalid(); }
            filter += " AND a.status=?"; args.add(parameters.get("status"));
        }
        if (query.before() != null) {
            var found = jdbc.queryForList("SELECT created_at FROM expense_report WHERE tenant_id=? AND employee_id=? AND id=?",
                    java.sql.Timestamp.class, actor.tenantId(), actor.userId(), query.before().toString());
            if (found.isEmpty()) throw invalid();
            filter += " AND (r.created_at<? OR (r.created_at=? AND r.id<?))";
            args.add(found.get(0)); args.add(found.get(0)); args.add(query.before().toString());
        }
        args.add(query.limit() + 1);
        var rows = jdbc.query("""
                SELECT r.id,r.application_id,a.business_no,a.title,a.status,a.round_no,a.version application_version,
                r.version financial_version,r.created_at
                FROM expense_report r JOIN approval_application a ON a.tenant_id=r.tenant_id AND a.id=r.application_id
                WHERE r.tenant_id=? AND r.employee_id=?
                """ + filter + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?", (row, index) -> new ReportItem(
                UUID.fromString(row.getString("id")), UUID.fromString(row.getString("application_id")), row.getString("business_no"),
                row.getString("title"), ApplicationStatus.valueOf(row.getString("status")), row.getInt("round_no"),
                row.getLong("application_version"), row.getLong("financial_version"), row.getTimestamp("created_at").toInstant()), args.toArray());
        var items = rows.stream().limit(query.limit()).toList();
        return new ReportPage(items, rows.size() > query.limit() ? items.get(items.size() - 1).id() : null);
    }

    /** 关闭的批准额度仍显示，补正可继续减少它已经预留的金额。 */
    public PriorPage requests(Map<String, String> parameters) {
        var page = page(parameters, Set.of("limit", "beforeId")); var ids = resourceIds(FinancialResourceStore.Kind.PRIOR_REQUEST, page);
        var values = requests.findAll(actors.actor().tenantId(), ids); var items = new ArrayList<PriorItem>();
        for (var id : ids.stream().limit(page.limit()).toList()) {
            var value = values.get(id); var lines = value.approvedLines().stream().map(line -> {
                var balance = value.balance(line.lineNo());
                return new PriorLine(line.lineNo(), line.approvedAmount(), balance.limit(), balance.available(), balance.reserved(), balance.consumed());
            }).toList();
            items.add(new PriorItem(id, value.applicationId(), value.legalEntityId(), value.version(), value.closed(), lines));
        }
        return new PriorPage(items, ids.size() > page.limit() ? items.get(items.size() - 1).id() : null);
    }

    /** 只显示已经确认放款的本人借款及余额，不以借款申请批准代替实际到账。 */
    public AdvancePage advances(Map<String, String> parameters) {
        var page = page(parameters, Set.of("limit", "beforeId")); var ids = resourceIds(FinancialResourceStore.Kind.ADVANCE, page);
        var values = advances.findAll(actors.actor().tenantId(), ids); var items = new ArrayList<AdvanceItem>();
        for (var id : ids.stream().limit(page.limit()).toList()) {
            var value = values.get(id); var balance = value.balance();
            items.add(new AdvanceItem(id, value.legalEntityId(), value.version(), value.status(), value.paidOn(), value.dueOn(),
                    balance.limit(), value.available(), balance.reserved(), balance.consumed(), value.repaid(), value.outstanding()));
        }
        return new AdvancePage(items, ids.size() > page.limit() ? items.get(items.size() - 1).id() : null);
    }

    private List<UUID> resourceIds(FinancialResourceStore.Kind kind, PageQuery page) {
        var actor = actors.actor(); var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.userId(), kind.name()));
        if (page.before() != null) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM finance_resource WHERE tenant_id=? AND owner_id=? AND resource_type=? AND id=?",
                    Integer.class, actor.tenantId(), actor.userId(), kind.name(), page.before().toString()) != 1) throw invalid();
            args.add(page.before().toString());
        }
        args.add(page.limit() + 1);
        return jdbc.query("SELECT id FROM finance_resource WHERE tenant_id=? AND owner_id=? AND resource_type=?"
                + (page.before() == null ? "" : " AND id<?") + " ORDER BY id DESC LIMIT ?", (row, index) -> UUID.fromString(row.getString("id")), args.toArray());
    }
    private static PageQuery page(Map<String, String> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet())) throw invalid();
        try {
            String rawLimit = parameters.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT));
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw invalid();
            int limit = Integer.parseInt(rawLimit);
            if (limit > MAX_LIMIT) throw invalid();
            UUID before = parameters.containsKey("beforeId") ? UUID.fromString(parameters.get("beforeId")) : null;
            if (before != null && !before.toString().equals(parameters.get("beforeId"))) throw invalid();
            return new PageQuery(limit, before);
        } catch (IllegalArgumentException invalid) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Expense query or owned pagination boundary is invalid"); }

    /**
     * 有界本人分页参数。
     * @author owlzhangfq@gmail.com
     */
    private record PageQuery(int limit, UUID before) { }
    /**
     * 本人报销摘要，不复制完整财务明细。
     * @author owlzhangfq@gmail.com
     */
    public record ReportItem(UUID id, UUID applicationId, String businessNo, String title, ApplicationStatus status, int roundNo,
                             long applicationVersion, long financialVersion, Instant createdAt) { }
    /**
     * 本人报销页按创建时间和编号稳定分页。
     * @author owlzhangfq@gmail.com
     */
    public record ReportPage(List<ReportItem> items, UUID nextBeforeId) { }
    /**
     * 批准行余额，不返回占用它的其他报销编号。
     * @author owlzhangfq@gmail.com
     */
    public record PriorLine(int lineNo, Money approved, Money limit, Money available, Money reserved, Money consumed) { }
    /**
     * 已批准事前额度的当前事实。
     * @author owlzhangfq@gmail.com
     */
    public record PriorItem(UUID id, UUID applicationId, UUID legalEntityId, long version, boolean closed, List<PriorLine> lines) { }
    /**
     * 当前主体的批准额度页。
     * @author owlzhangfq@gmail.com
     */
    public record PriorPage(List<PriorItem> items, UUID nextBeforeId) { }
    /**
     * 已放款余额不带账户、付款引用或其他单据归属。
     * @author owlzhangfq@gmail.com
     */
    public record AdvanceItem(UUID id, UUID legalEntityId, long version, EmployeeAdvance.Status status, LocalDate paidOn, LocalDate dueOn,
                              Money paid, Money available, Money reserved, Money settled, Money repaid, Money outstanding) { }
    /**
     * 当前主体的已放款余额页。
     * @author owlzhangfq@gmail.com
     */
    public record AdvancePage(List<AdvanceItem> items, UUID nextBeforeId) { }
}
