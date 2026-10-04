package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.organization.InitiatorContext;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次费用预检的持久状态；任务恢复不把未知外部结果当作通过。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePrecheckJob(Input input, long version, Status status, Instant createdAt, Instant startedAt,
        Instant leaseUntil, Instant completedAt, Result result) {
    /** 排队、领取和终态各保留一个版本，所有输入始终不变。 */
    public ExpensePrecheckJob {
        Objects.requireNonNull(input); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        boolean active = status == Status.QUEUED || status == Status.RUNNING;
        if (version < 1 || version > 3 || startedAt != null && startedAt.isBefore(createdAt)
                || status == Status.QUEUED && (version != 1 || startedAt != null || leaseUntil != null)
                || status != Status.QUEUED && (startedAt == null || leaseUntil == null || !leaseUntil.isAfter(startedAt))
                || status == Status.RUNNING && version != 2
                || active && (completedAt != null || result != null)
                || !active && (version != 3 || completedAt == null || completedAt.isBefore(startedAt) || result == null)
                || !active && result.status() != status) throw invalid();
        if (status == Status.READY) validateEvidence(input, result.evidence(), completedAt);
        if (!active && result.observation() != null && (result.observation().observedAt().isBefore(startedAt)
                || result.observation().observedAt().isAfter(completedAt))) throw invalid();
    }

    /** 初次入库只登记输入，不修改任何财务资源。 */
    public static ExpensePrecheckJob queue(Input input, Instant now) {
        return new ExpensePrecheckJob(input, 1, Status.QUEUED, now, null, null, null, null);
    }
    /** 单次领取的租约不因重启或网络慢而延长。 */
    public ExpensePrecheckJob start(Instant now, Instant until) {
        if (status != Status.QUEUED) throw conflict();
        return new ExpensePrecheckJob(input, 2, Status.RUNNING, createdAt, now, until, null, null);
    }
    /** 迟到结果统一超时；终态不可被后续成功覆盖。 */
    public ExpensePrecheckJob finish(Result value, Instant now) {
        if (status != Status.RUNNING) throw conflict();
        Result completed = expired(now) ? Result.unavailable(Stage.SYSTEM, "TIMEOUT") : Objects.requireNonNull(value);
        return new ExpensePrecheckJob(input, 3, completed.status(), createdAt, startedAt, leaseUntil, now, completed);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }

    private static void validateEvidence(Input input, ExpensePrecheckEvidence evidence, Instant completedAt) {
        var preview = evidence.preview(); var budget = evidence.budget().request();
        if (!evidence.validUntil().isAfter(completedAt) || !input.initiator().legalEntityId().equals(evidence.legalEntity().id())
                || preview.roundNo() != input.roundNo() || preview.submittedFinancialVersion() != input.financialVersion()
                || !preview.submittedBy().equals(input.employeeId()) || !preview.account().employeeId().equals(input.employeeId())
                || !input.reportId().equals(budget.reportId()) || budget.roundNo() != input.roundNo()
                || budget.financialVersion() != input.financialVersion() || !budget.employeeId().equals(input.employeeId())
                || !budget.legalEntityId().equals(evidence.legalEntity().id()) || !budget.accountingDate().equals(input.accountingDate())) throw invalid();
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PRECHECK", "Expense precheck state or evidence is invalid"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_PRECHECK_STATE_CONFLICT", "Expense precheck is no longer executable"); }

    /**
     * 固定申请与财务版本、任职和记账日期，实际员工始终来自认证身份。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID reportId, UUID applicationId, String employeeId,
            long applicationVersion, long financialVersion, int roundNo, long attempt, InitiatorContext initiator,
            LocalDate accountingDate, String targetDigest) {
        /** 任职必须属于本次员工；法人匹配由排队用例核对当前草稿。 */
        public Input {
            if (id == null || reportId == null || applicationId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || applicationVersion < 1 || financialVersion < 1
                    || roundNo < 1 || attempt < 1 || initiator == null || initiator.appointmentId() == null || initiator.legalEntityId() == null
                    || !employeeId.equals(initiator.subject()) || accountingDate == null
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid();
        }
    }
    /**
     * READY 只是有时效的提交候选；BLOCKED 与依赖不可用分别展示。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, UNAVAILABLE }
    /**
     * 对外可定位的检查阶段，不透传远端错误正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Stage { INPUT, CATALOG, ACCOUNT, INVOICE, RATE, POLICY, RESOURCES, BUDGET, CONTEXT, SYSTEM }
    /**
     * 业务不满足与没有可信结论采用不同分类。
     * @author owlzhangfq@gmail.com
     */
    public enum Nature { REJECTED, UNAVAILABLE }
    /**
     * 逐行稳定错误；整单或上下文错误没有行号。
     * @author owlzhangfq@gmail.com
     */
    public record Finding(Stage stage, Integer lineNo, Nature nature, String code) {
        /** 只保存受控分类，不接收错误文本或任何凭据。 */
        public Finding {
            if (stage == null || nature == null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")
                    || lineNo != null && (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES)) throw invalid();
        }
    }
    /**
     * 完整事实和错误列表互斥，部分成功不得生成可提交快照。
     * @author owlzhangfq@gmail.com
     */
    public record Result(ExpensePrecheckEvidence evidence, List<Finding> findings, ExpensePrecheckObservation observation) {
        /** 历史检查没有解释授权依据，读取时不补造观察时间或当前制度版本。 */
        public Result(ExpensePrecheckEvidence evidence, List<Finding> findings) { this(evidence, findings, null); }

        /** 每行最多一个主错误，另允许少量整单和上下文错误。 */
        public Result {
            findings = List.copyOf(findings);
            if ((evidence == null) == findings.isEmpty() || findings.size() > ExpenseContent.MAX_LINES + 10) throw invalid();
            if (evidence != null && observation != null && (!Objects.equals(evidence.policySelection(), observation.policySelection())
                    || observation.validUntil().isAfter(evidence.validUntil()))) throw invalid();
        }
        /** 附加解释所需的观察，不改变规则结论、逐行问题或可提交证据。 */
        public Result observed(ExpensePrecheckObservation value) { return new Result(evidence, findings, value); }
        /** 单个依赖失败同样保留明确阶段。 */
        public static Result unavailable(Stage stage, String code) { return new Result(null, List.of(new Finding(stage, null, Nature.UNAVAILABLE, code))); }
        /** 根据证据决定终态，不由调用方另传“通过”标记。 */
        public Status status() { return evidence != null ? Status.READY : findings.stream().anyMatch(value -> value.nature() == Nature.UNAVAILABLE) ? Status.UNAVAILABLE : Status.BLOCKED; }
    }
}
