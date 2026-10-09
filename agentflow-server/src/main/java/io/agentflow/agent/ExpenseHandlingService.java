package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.ExpenseDraftService;
import io.agentflow.expense.ExpensePolicyGuidance;
import io.agentflow.expense.ExpensePolicyGuidanceService;
import io.agentflow.expense.ExpensePrecheckService;
import io.agentflow.expense.ExpenseReportRepository;
import io.agentflow.expense.ExpenseResponse;
import io.agentflow.expense.InvoiceWalletService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人办理编排与四种只读工具；原资料必须先授权，外部制度读取在事务外完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseHandlingService {
    private final CurrentActor actors;
    private final ExpenseReportRepository reports;
    private final ApprovalApplicationFacade applications;
    private final ExpenseDraftService drafts;
    private final InvoiceWalletService invoices;
    private final ExpensePolicyGuidanceService policies;
    private final ExpensePrecheckService prechecks;
    private final ExpenseHandlingRepository repository;
    private final JsonUtil json;

    /** 查询只组合现有授权用例，没有通用 SQL、脚本、批准或付款入口。 */
    public ExpenseHandlingService(CurrentActor actors, ExpenseReportRepository reports, ApprovalApplicationFacade applications,
            ExpenseDraftService drafts, InvoiceWalletService invoices, ExpensePolicyGuidanceService policies,
            ExpensePrecheckService prechecks, ExpenseHandlingRepository repository, JsonUtil json) {
        this.actors = actors; this.reports = reports; this.applications = applications; this.drafts = drafts;
        this.invoices = invoices; this.policies = policies; this.prechecks = prechecks; this.repository = repository; this.json = json;
    }

    /** 幂等回放与历史查询也必须重新核对当前本人资格。 */
    @Transactional(readOnly = true)
    public void authorize(UUID reportId) {
        var actor = actors.actor();
        var report = reports.find(actor.tenantId(), reportId).filter(value -> value.employeeId().equals(actor.userId())).orElseThrow(ExpenseHandlingService::notFound);
        applications.requireApplicant(report.applicationId());
    }

    /** 只在当前可编辑双版本上由本人显式创建，一单一个活动办理记录。 */
    @Transactional
    public View start(UUID reportId, long applicationVersion, long financialVersion, String goal) {
        authorize(reportId); var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var expense = drafts.read(reportId, null); requireCurrent(expense, applicationVersion, financialVersion);
        if (repository.active(actor.tenantId(), reportId).isPresent()) throw new DomainException("AGENT_HANDLING_ACTIVE", "Expense report already has an active handling record");
        var task = ExpenseHandlingTask.start(new ExpenseHandlingTask.Context(UUID.randomUUID(), actor.tenantId(), reportId,
                expense.applicationId(), actor.userId(), goal, now()), applicationVersion, financialVersion);
        repository.create(task); return view(task, expense);
    }

    /** 历史保留旧版本和步骤；当前可继续标记每次从实际单据计算。 */
    @Transactional(readOnly = true)
    public List<View> list(UUID reportId) {
        authorize(reportId); var expense = drafts.read(reportId, null);
        return repository.list(actors.actor().tenantId(), reportId).stream().map(task -> view(task, expense)).toList();
    }

    /** 关闭只结束本记录，界面必须明确原已授权任务仍按原号执行。 */
    @Transactional
    public View close(UUID reportId, UUID id, long expectedVersion) {
        authorize(reportId); var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var task = requireTask(reportId, id); task.close(expectedVersion, now()); repository.save(task, expectedVersion);
        return view(task, drafts.read(reportId, null));
    }

    /** 白名单工具不会自动外发模型；员工先核对返回事实，再沿原助手入口确认发送。 */
    @Transactional(propagation = Propagation.NEVER)
    public Prepared prepare(UUID reportId, UUID taskId, long expectedVersion, ReadTool tool, UUID referenceId, Integer lineNo) {
        authorize(reportId); var task = requireTask(reportId, taskId); task.requireVersion(expectedVersion);
        var expense = drafts.read(reportId, null); requireCurrent(expense, task.state().applicationVersion(), task.state().financialVersion());
        if (!task.active() || task.state().steps().size() >= ExpenseHandlingTask.MAX_STEPS) throw new DomainException("AGENT_HANDLING_CLOSED", "Expense handling record cannot accept more steps");
        ToolResult result;
        UUID reference; long version;
        switch (tool) {
            case EXPENSE -> {
                if (referenceId != null || lineNo != null) throw invalid();
                reference = reportId; version = expense.financialVersion(); result = new ToolResult(tool, expense, null, null, null);
            }
            case INVOICE -> {
                if (referenceId == null || lineNo != null) throw invalid();
                var invoice = invoices.get(referenceId); reference = invoice.id(); version = invoice.version();
                result = new ToolResult(tool, null, invoice, null, null);
            }
            case POLICY -> {
                if (lineNo == null || referenceId != null) throw invalid();
                var line = expense.content().lines().stream().filter(value -> value.lineNo() == lineNo).findFirst().orElseThrow(ExpenseHandlingService::invalid);
                var policy = policies.read(new ExpensePolicyGuidance.Context(expense.content().legalEntityId(), expense.content().type(),
                        line.categoryCode(), line.cityCode(), line.incurredOn(), line.claimedGross().currency(), line.unit(), line.endedOn()));
                reference = policy.guidance().policyId(); version = policy.guidance().policyVersion();
                result = new ToolResult(tool, null, null, policy, null);
            }
            case PRECHECK_RESULT -> {
                if (referenceId == null || lineNo != null) throw invalid();
                var precheck = prechecks.get(reportId, referenceId); reference = precheck.job().id(); version = precheck.job().version();
                if (precheck.job().applicationVersion() != expense.applicationVersion() || precheck.job().financialVersion() != expense.financialVersion()) throw changed();
                result = new ToolResult(tool, null, null, null, precheck);
            }
            default -> throw invalid();
        }
        return new Prepared(taskId, expectedVersion, expense.applicationVersion(), expense.financialVersion(), reference, version,
                AssistConfiguration.digest(json.write(result)), result, now());
    }

    /** 外部读取后再核对版本，不能将另一版本的制度或费用结果记作本次依据。 */
    @Transactional
    public ToolReceipt record(UUID reportId, Prepared prepared) {
        authorize(reportId); var actor = actors.actor(); reports.lock(actor.tenantId(), reportId);
        var task = requireTask(reportId, prepared.taskId()); task.requireVersion(prepared.expectedVersion());
        var expense = drafts.read(reportId, null); requireCurrent(expense, prepared.applicationVersion(), prepared.financialVersion());
        if (!task.active()) throw new DomainException("AGENT_HANDLING_CLOSED", "Expense handling record is closed");
        // 原请求幂等保证同一次读取只追加一个步骤，引用仍使用原始业务标识。
        task.observe(ExpenseHandlingTask.Tool.valueOf(prepared.result().tool().name()), prepared.reference(), prepared.sourceVersion(), "READ",
                prepared.applicationVersion(), prepared.financialVersion(), prepared.digest(), prepared.at(), now());
        repository.save(task, prepared.expectedVersion()); return new ToolReceipt(view(task, expense), prepared.result());
    }

    private ExpenseHandlingTask requireTask(UUID reportId, UUID id) {
        var actor = actors.actor();
        return repository.find(actor.tenantId(), id).filter(task -> task.context().reportId().equals(reportId)
                && task.context().ownerId().equals(actor.userId())).orElseThrow(ExpenseHandlingService::notFound);
    }
    private static View view(ExpenseHandlingTask task, ExpenseResponse expense) {
        var c = task.context(); var s = task.state();
        boolean current = task.active() && expense.editable() && expense.applicationVersion() == s.applicationVersion() && expense.financialVersion() == s.financialVersion();
        return new View(c.id(), c.reportId(), c.applicationId(), c.goal(), c.createdAt(), s.version(), s.status(), s.applicationVersion(), s.financialVersion(), current, s.steps());
    }
    private static void requireCurrent(ExpenseResponse expense, long applicationVersion, long financialVersion) {
        if (!expense.editable() || expense.applicationVersion() != applicationVersion || expense.financialVersion() != financialVersion) throw changed();
    }
    private static DomainException changed() { return new DomainException("AGENT_INPUT_CHANGED", "Expense handling versions changed"); }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_INPUT", "Tool parameters do not match the selected expense operation"); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense handling record not found"); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }

    /**
     * 四种真实只读用例，模型正文不能新增枚举成员或覆盖身份。
     * @author owlzhangfq@gmail.com
     */
    public enum ReadTool { EXPENSE, INVOICE, POLICY, PRECHECK_RESULT }
    /**
     * 有界办理视图不包含租户凭据或模型原始输入。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID reportId, UUID applicationId, String goal, Instant createdAt, long version,
            ExpenseHandlingTask.Status status, long applicationVersion, long financialVersion, boolean current, List<ExpenseHandlingTask.Step> steps) { }
    /**
     * 每种工具只有一种强类型结果，不接受模型或客户端自造事实。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResult(ReadTool tool, ExpenseResponse expense, InvoiceWalletService.Item invoice,
            ExpensePolicyGuidanceService.View policy, ExpensePrecheckService.View precheck) { }
    /**
     * 事务外读取结果只在服务器内部传递。
     * @author owlzhangfq@gmail.com
     */
    public record Prepared(UUID taskId, long expectedVersion, long applicationVersion, long financialVersion, UUID reference,
            long sourceVersion, String digest, ToolResult result, Instant at) { }
    /**
     * 幂等恢复返回同一次读取与步骤，不重新执行外部查询。
     * @author owlzhangfq@gmail.com
     */
    public record ToolReceipt(View task, ToolResult result) { }
}
