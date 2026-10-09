package io.agentflow.expense;


import static io.agentflow.expense.ExpensePrecheckEvidence.ResourceKind;
import static io.agentflow.expense.ExpensePrecheckEvidence.ResourceVersion;

import io.agentflow.common.DomainException;
import io.agentflow.expense.mapper.ExpensePrecheckResourcesMapper;
import io.agentflow.mybatis.SqlRows;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 预检按实际引用批量读取资源，最终提交复用同一版本和成功验票凭据校验。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePrecheckResources {
    private final ExpensePrecheckResourcesMapper sqlMapper;
    private final InvoiceRepository invoices;
    private final ExpenseRequestRepository requests;
    private final EmployeeAdvanceRepository advances;
    private final JdbcInvoiceVerificationRepository verifications;

    /** 保留各自仓储的快照一致性校验，不从未经验证的 JSON 拼出余额。 */
    public ExpensePrecheckResources(
            InvoiceRepository invoices,
            ExpenseRequestRepository requests,
            EmployeeAdvanceRepository advances,
            JdbcInvoiceVerificationRepository verifications,
            ExpensePrecheckResourcesMapper sqlMapper) {
        this.sqlMapper = sqlMapper;
        this.invoices = invoices;
        this.requests = requests;
        this.advances = advances;
        this.verifications = verifications;
    }

    /** 正式提交在同一短事务固定资源引用，阻止新验票任务在复核凭据后插入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockReferences(String tenant, List<ResourceVersion> references) {
        for (var kind : ResourceKind.values()) {
            var ids =
                    references.stream()
                            .filter(value -> value.kind() == kind)
                            .map(value -> value.id().toString())
                            .sorted()
                            .toList();
            if (!ids.isEmpty())
                sqlMapper.lockReferences(Map.of("tenant", tenant, "kind", kind.name(), "ids", ids));
        }
    }

    /** 当前输入与上一轮占用都参与重提，不能漏掉本次被删除的引用。 */
    public ExpenseSubmissionResources.Resources load(ExpenseReport report) {
        return load(report, true);
    }

    /** 取消补正单时只读取已提交引用，新草稿可能已经删除或替换了原占用。 */
    public ExpenseSubmissionResources.Resources loadReserved(ExpenseReport report) {
        return load(report, false);
    }

    private ExpenseSubmissionResources.Resources load(ExpenseReport report, boolean includeDraft) {
        var invoiceIds = new HashSet<UUID>(); var requestIds = new HashSet<UUID>(); var advanceIds = new HashSet<UUID>();
        if (includeDraft) {
            collect(report.content(), invoiceIds, requestIds);
            report.content().advanceOffsets().forEach(value -> advanceIds.add(value.advanceId()));
        }
        if (!report.rounds().isEmpty()) {
            collect(report.currentRound().content(), invoiceIds, requestIds);
            report.currentRound().advanceOffsets().forEach(value -> advanceIds.add(value.advanceId()));
        }
        var invoiceValues = invoices.findAll(report.tenantId(), invoiceIds);
        var requestValues = requests.findAll(report.tenantId(), requestIds);
        var advanceValues = advances.findAll(report.tenantId(), advanceIds);
        if (invoiceValues.size() != invoiceIds.size() || requestValues.size() != requestIds.size() || advanceValues.size() != advanceIds.size()
                || invoiceValues.values().stream().anyMatch(value -> !value.ownerId().equals(report.employeeId()))
                || requestValues.values().stream().anyMatch(value -> !value.employeeId().equals(report.employeeId()))
                || advanceValues.values().stream().anyMatch(value -> !value.employeeId().equals(report.employeeId()))) {
            throw new DomainException("EXPENSE_RESOURCE_UNAVAILABLE", "Referenced financial resources are unavailable to this employee");
        }
        return new ExpenseSubmissionResources.Resources(invoiceValues.values().stream().collect(Collectors.toUnmodifiableMap(Invoice::id, Invoice::state)),
                requestValues.values().stream().collect(Collectors.toUnmodifiableMap(ExpenseRequest::id, ExpenseRequest::state)),
                advanceValues.values().stream().collect(Collectors.toUnmodifiableMap(EmployeeAdvance::id, EmployeeAdvance::state)));
    }

    /** 有效占用键也要参与预检，不能只检查当前文件是否 AVAILABLE。 */
    public void requireClaimsAvailable(String tenant, ExpenseSubmissionResources.Plan plan) {
        var released =
                plan.invoices().stream()
                        .filter(
                                change ->
                                        change.operation()
                                                == ExpenseSubmissionResources.Operation.RELEASE)
                        .map(change -> change.after().id())
                        .collect(Collectors.toSet());
        var wanted =
                plan.invoices().stream()
                        .filter(
                                change ->
                                        change.operation()
                                                != ExpenseSubmissionResources.Operation.RELEASE)
                        .collect(
                                Collectors.toMap(
                                        change -> change.after().facts().key().canonical(),
                                        change -> change.after().id()));
        if (wanted.isEmpty()) return;
        var claims =
                SqlRows.map(
                        sqlMapper.requireClaimsAvailable(
                                Map.of("tenant", tenant, "keys", wanted.keySet())),
                        row ->
                                new InvoiceClaim(
                                        row.getString("invoice_key"),
                                        row.getString("invoice_id") == null
                                                ? null
                                                : UUID.fromString(row.getString("invoice_id"))));
        if (claims.stream()
                .anyMatch(
                        value ->
                                value.invoiceId() == null
                                        || !wanted.get(value.key()).equals(value.invoiceId())
                                                && !released.contains(value.invoiceId()))) {
            throw new DomainException(
                    "INVOICE_OCCUPIED",
                    "A canonical invoice has another active or consumed occupation");
        }
    }

    /** 稳定排序便于比较和后续锁顺序；包括只准备释放的旧资源。 */
    public static List<ResourceVersion> versions(ExpenseSubmissionResources.Resources values) {
        var result = new ArrayList<ResourceVersion>();
        values.invoices().forEach((id, value) -> result.add(new ResourceVersion(ResourceKind.INVOICE, id, value.version())));
        values.requests().forEach((id, value) -> result.add(new ResourceVersion(ResourceKind.PRIOR_REQUEST, id, value.version())));
        values.advances().forEach((id, value) -> result.add(new ResourceVersion(ResourceKind.ADVANCE, id, value.version())));
        result.sort(Comparator.comparing(ResourceVersion::kind).thenComparing(value -> value.id().toString()));
        return List.copyOf(result);
    }

    /** 再次验票即使失败且未改变发票版本，也使先前成功凭据失效。 */
    public boolean current(ExpenseReport report, ExpensePrecheckEvidence evidence) {
        return currentProblem(report, evidence) == null;
    }

    /** 提交保留原资源拒绝原因，公开错误码仍为原有的资源变化。 */
    public void requireCurrentForSubmission(ExpenseReport report, ExpensePrecheckEvidence evidence) {
        var problem = currentProblem(report, evidence);
        if (problem != null) {
            var failure = changed(); failure.initCause(problem); throw failure;
        }
    }

    private DomainException currentProblem(ExpenseReport report, ExpensePrecheckEvidence evidence) {
        try {
            var loaded = load(report);
            // 先在副本上复核原领域占用规则，保留同原件和同票号竞争的明确原因；此处不保存计划。
            var prepared = ExpenseReport.restore(report.state()); var preview = evidence.preview();
            var assessments = preview.originalLines().stream().collect(Collectors.toMap(value -> value.original().lineNo(), ExpenseRound.FrozenLine::assessment));
            prepared.freeze(report.version(), preview.roundNo(), preview.baseCurrency(), preview.account(), assessments, report.employeeId(), java.time.Instant.now());
            requireClaimsAvailable(report.tenantId(), new ExpenseSubmissionResources().plan(prepared, loaded, java.time.Instant.now()));
            if (!versions(loaded).equals(evidence.resources())) return changed();
            var receipts = verifications.currentReceipts(report.tenantId(), evidence.invoices().stream().map(ExpensePrecheckEvidence.InvoiceReceipt::invoiceId).toList());
            if (!evidence.invoices().stream().allMatch(value -> receipts.containsKey(value.invoiceId())
                    && receipts.get(value.invoiceId()).input().id().equals(value.verificationId())
                    && receipts.get(value.invoiceId()).resultingInvoiceVersion() == value.invoiceVersion())) return changed();
            return null;
        } catch (DomainException changed) { return changed; }
    }

    private static DomainException changed() { return new DomainException("RESOURCES_CHANGED", "Expense precheck must be refreshed before submission"); }

    private static void collect(ExpenseContent content, Set<UUID> invoices, Set<UUID> requests) {
        for (var line : content.lines()) {
            invoices.addAll(line.invoiceIds());
            if (line.priorRequest() != null) requests.add(line.priorRequest().requestId());
        }
    }

    /**
     * 原采购应付没有员工上传文件标识，仍是有效且不可被报销释放的票号归属。
     *
     * @author owlzhangfq@gmail.com
     */
    private record InvoiceClaim(String key, UUID invoiceId) {}
}
