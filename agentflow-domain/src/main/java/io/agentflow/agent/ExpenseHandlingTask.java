package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 一次本人报销办理的有界步骤记录；模型任务、费用检查和正式审批各自保留原状态。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseHandlingTask {
    public static final int MAX_STEPS = 32;
    private final Context context;
    private State state;

    private ExpenseHandlingTask(Context context, State state) { this.context = context; this.state = state; }

    /** 创建显式办理目标，不能把缺少业务资料解释为可提交。 */
    public static ExpenseHandlingTask start(Context context, long applicationVersion, long financialVersion) {
        return new ExpenseHandlingTask(context, new State(1, Status.OPEN, applicationVersion, financialVersion, List.of(), context.createdAt()));
    }

    /** 原步骤和人工确认只从完整持久状态恢复，不通过重跑模型恢复历史。 */
    public static ExpenseHandlingTask restore(Context context, State state) { return new ExpenseHandlingTask(context, state); }

    /** 同一子运行更新原步骤；晚到的旧版本不能倒退原状态，旧任务不能混入新办理。 */
    public boolean observe(Tool tool, UUID referenceId, long sourceVersion, String outcome, long applicationVersion,
            long financialVersion, String inputDigest, Instant occurredAt, Instant now) {
        if (!active() || occurredAt.isBefore(context.createdAt())) return false;
        var steps = new ArrayList<>(state.steps());
        int index = -1;
        for (int i = 0; i < steps.size(); i++) if (tool != Tool.EXPENSE && tool != Tool.INVOICE && tool != Tool.POLICY && tool != Tool.PRECHECK_RESULT && steps.get(i).tool() == tool && steps.get(i).referenceId().equals(referenceId)) { index = i; break; }
        if (index >= 0 && steps.get(index).sourceVersion() >= sourceVersion) return false;
        if (index < 0 && steps.size() >= MAX_STEPS) {
            state = new State(state.version() + 1, Status.LIMIT_REACHED, state.applicationVersion(), state.financialVersion(), steps, now);
            return true;
        }
        int number = index < 0 ? steps.size() + 1 : index + 1;
        var step = new Step(number, tool, referenceId, sourceVersion, outcome, applicationVersion, financialVersion, inputDigest,
                index < 0 ? occurredAt : steps.get(index).startedAt(), now);
        if (index < 0) steps.add(step); else steps.set(index, step);
        Status next = state.status();
        if (number == steps.size() || tool == Tool.EXPENSE_SAVED) {
            next = switch (outcome) {
                case "QUEUED", "RUNNING" -> Status.WAITING;
                case "BLOCKED", "UNAVAILABLE", "FAILED" -> Status.NEEDS_INFORMATION;
                case "COMPLETED" -> Status.NEEDS_CONFIRMATION;
                case "SUBMITTED" -> Status.SUBMITTED;
                default -> Status.OPEN;
            };
        }
        long app = state.applicationVersion(), finance = state.financialVersion();
        if (tool == Tool.EXPENSE_SAVED || tool == Tool.SUBMISSION) { app = applicationVersion; finance = financialVersion; }
        if (next != Status.SUBMITTED) {
            long currentApp = app, currentFinance = finance;
            // 同类任务的较新尝试替代旧结论；完整历史仍保留在步骤列表中。
            var latest = new EnumMap<Tool, Step>(Tool.class);
            steps.stream().filter(value -> value.applicationVersion() == currentApp && value.financialVersion() == currentFinance)
                    .forEach(value -> latest.put(value.tool(), value));
            var current = latest.values();
            if (current.stream().anyMatch(stepValue -> stepValue.outcome().equals("QUEUED") || stepValue.outcome().equals("RUNNING"))) next = Status.WAITING;
            else if (current.stream().anyMatch(stepValue -> stepValue.outcome().equals("COMPLETED"))) next = Status.NEEDS_CONFIRMATION;
            else if (current.stream().anyMatch(stepValue -> stepValue.outcome().equals("BLOCKED") || stepValue.outcome().equals("UNAVAILABLE") || stepValue.outcome().equals("FAILED"))) next = Status.NEEDS_INFORMATION;
        }
        state = new State(state.version() + 1, next, app, finance, steps, now);
        return true;
    }

    /** 只结束办理记录，不取消已获授权的子任务，不产生批准或付款事实。 */
    public void close(long expectedVersion, Instant now) {
        requireVersion(expectedVersion);
        if (!active()) throw new DomainException("AGENT_HANDLING_CLOSED", "Expense handling record is already closed");
        state = new State(state.version() + 1, Status.CLOSED, state.applicationVersion(), state.financialVersion(), state.steps(), now);
    }

    /** 用户命令绑定当前办理版本，后台步骤推进后需要重新读取。 */
    public void requireVersion(long version) {
        if (state.version() != version) throw new DomainException("CONCURRENCY_CONFLICT", "Expense handling record changed");
    }
    public boolean active() { return state.status() != Status.CLOSED && state.status() != Status.SUBMITTED && state.status() != Status.LIMIT_REACHED; }
    public Context context() { return context; }
    public State state() { return state; }

    /**
     * 服务器固定本人、单据与创建时间；目标说明不会被自动发送给模型。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, String tenantId, UUID reportId, UUID applicationId, String ownerId, String goal, Instant createdAt) {
        public Context {
            Objects.requireNonNull(id); Objects.requireNonNull(reportId); Objects.requireNonNull(applicationId); Objects.requireNonNull(createdAt);
            if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(ownerId) || StringUtils.isBlank(goal) || goal.length() > 1000) throw new IllegalArgumentException("Invalid expense handling identity or goal");
        }
    }
    /**
     * 状态只控制办理界面，不替代费用预检、申请状态或人工审批结论。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { OPEN, WAITING, NEEDS_INFORMATION, NEEDS_CONFIRMATION, SUBMITTED, CLOSED, LIMIT_REACHED }
    /**
     * 实际费用场景的白名单；没有任意查询、脚本、批准或付款工具。
     * @author owlzhangfq@gmail.com
     */
    public enum Tool { EXPENSE, INVOICE, POLICY, PRECHECK_RESULT, DRAFT, PRECHECK, EXPLANATION, EXPENSE_SAVED, CORRECTION, SUBMISSION }
    /**
     * 步骤引用原始事实和版本，仅保留摘要，不复制票面、账户或模型来源正文。
     * @author owlzhangfq@gmail.com
     */
    public record Step(int number, Tool tool, UUID referenceId, long sourceVersion, String outcome, long applicationVersion,
            long financialVersion, String inputDigest, Instant startedAt, Instant updatedAt) { }
    /**
     * 版本和步骤必须有界，恢复与新建共用同一不变量。
     * @author owlzhangfq@gmail.com
     */
    public record State(long version, Status status, long applicationVersion, long financialVersion, List<Step> steps, Instant updatedAt) {
        public State {
            Objects.requireNonNull(status); Objects.requireNonNull(updatedAt); steps = List.copyOf(steps);
            if (version < 1 || applicationVersion < 1 || financialVersion < 1 || steps.size() > MAX_STEPS) throw new IllegalArgumentException("Invalid expense handling state");
        }
    }
}
