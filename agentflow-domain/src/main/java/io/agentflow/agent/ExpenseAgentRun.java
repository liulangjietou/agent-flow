package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 有界办理循环：模型只决定下一种动作，查询、人工确认和恢复各有明确状态。
 * @author owlzhangfq@gmail.com
 */
public final class ExpenseAgentRun {
    public static final String PROMPT_VERSION = "expense-handling-agent-v1";
    public static final int MAX_STEPS = 12;
    public static final int AUTHORIZATION_SECONDS = 30 * 60;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_TOOL_REFERENCES = 20;
    private static final int MAX_PRECHECK_REFERENCES = 10;
    private final Context context;
    private State state;
    /** 一次授权只对应固定身份、目标、单据版本和白名单范围。 */
    public ExpenseAgentRun(Context context) {
        this.context = context;
        this.state = new State(1, Status.READY, List.of(), List.of(), null, null, null, null, context.createdAt());
    }
    /** 恢复原步骤，不通过模型重新生成历史。 */
    public static ExpenseAgentRun restore(Context context, State state) {
        var run = new ExpenseAgentRun(context); run.state = state; return run;
    }
    /** 先形成持久模型步骤；租约过期后只标记中断，不静默重发。 */
    public Step plan(Instant now, int seconds) {
        require(Status.READY);
        if (state.steps().size() >= context.scope().maxSteps()) { stop(Status.LIMIT_REACHED, "本次执行已达到授权步数上限。", now); return null; }
        var steps = new ArrayList<>(state.steps());
        var step = new Step(UUID.randomUUID(), now, null, "MODEL_RUNNING", null);
        steps.add(step); set(Status.MODEL_RUNNING, steps, state.answers(), null, now.plusSeconds(seconds), null, null, now); return step;
    }
    /** 只允许授权白名单，身份、地址和脚本均不是模型参数。 */
    public void decided(Decision decision, Instant now) {
        requireLease(Status.MODEL_RUNNING, now); decision.requireAllowed(context.scope());
        var status = switch (decision.action()) {
            case ASK_USER -> Status.NEEDS_INFORMATION;
            case FINISH -> Status.COMPLETED;
            case EXTRACT_INVOICE, DRAFT -> Status.NEEDS_CONFIRMATION;
            default -> Status.TOOL_READY;
        };
        replace(new Step(last().id(), last().createdAt(), decision, status.name(), null), status, decision.message(), null, null, null, now);
    }
    /** 工具调用领取原动作，崩溃恢复不能生成另一个动作身份。 */
    public void startTool(Instant now) {
        require(Status.TOOL_READY); set(Status.TOOL_RUNNING, state.steps(), state.answers(), null, now.plusSeconds(HandlingReadExecution.LEASE_SECONDS), null, null, now);
    }
    /** 原工具结果供下一次决策读取；调用历史和结果一同保留。 */
    public void observed(String result, Instant now) {
        requireLease(Status.TOOL_RUNNING, now);
        replace(new Step(last().id(), last().createdAt(), last().decision(), "READ", result), Status.READY, null, null, null, null, now);
    }
    /** 人工排队后的真实运行与当前动作绑定，不能关联另一张票据。 */
    public void bind(Action action, UUID invoiceId, UUID childId, Instant now) {
        require(Status.NEEDS_CONFIRMATION);
        if (last().decision().action() != action || !Objects.equals(last().decision().referenceId(), invoiceId)) throw invalid();
        set(Status.WAITING_CHILD, state.steps(), state.answers(), "等待原任务完成及本人确认。", null, childId, action, now);
    }
    /** 票据本人确认后直接进入费用草稿准备；草稿确认后由本人保存原表单。 */
    public void childConfirmed(String result, Instant now) {
        require(Status.WAITING_CHILD);
        boolean invoice = state.childAction() == Action.EXTRACT_INVOICE;
        var completed = new Step(last().id(), last().createdAt(), last().decision(), "CONFIRMED", result);
        var steps = new ArrayList<>(state.steps()); steps.set(steps.size() - 1, completed);
        if (invoice) {
            if (steps.size() >= context.scope().maxSteps()) { set(Status.LIMIT_REACHED, steps, state.answers(), "票据已确认；授权步数已用完，请从原费用草稿继续。", null, null, null, now); return; }
            steps.add(new Step(UUID.randomUUID(), now, new Decision(Action.DRAFT, null, null, "票据已确认，请核对费用草稿的行程、目录及发送范围。"), "NEEDS_CONFIRMATION", null));
        }
        set(invoice ? Status.NEEDS_CONFIRMATION : Status.COMPLETED, steps, state.answers(),
                invoice ? "票据已确认，请继续整理费用草稿。" : "草稿已由本人确认，请在费用表单核对后保存并预检。", null, null, null, now);
    }
    /** 子任务失败保留原编号，必须由本人处理，不能偷偷生成新任务。 */
    public void childFailed(String failure, Instant now) { require(Status.WAITING_CHILD); stop(Status.FAILED, failure, now); }
    /** 恢复时区分可安全重读的工具和结果未知的模型。 */
    public void recover(Instant now) {
        if (state.leaseUntil() == null || state.leaseUntil().isAfter(now)) return;
        if (state.status() == Status.MODEL_RUNNING) stop(Status.INTERRUPTED, "原模型执行结果未知，请确认后继续新的决策；不会重发原模型步骤。", now);
        else if (state.status() == Status.TOOL_RUNNING) set(Status.TOOL_READY, state.steps(), state.answers(), null, null, null, null, now);
    }
    /** 补充回答或明确接受未知调用后继续，历史与原模型步骤保持不变。 */
    public void resume(long version, String answer, boolean acknowledgeUnknown, Instant now) {
        requireVersion(version);
        if (state.status() == Status.FAILED && !state.steps().isEmpty() && last().decision() != null
                && List.of(Action.EXPENSE, Action.INVOICE, Action.POLICY, Action.PRECHECK_RESULT).contains(last().decision().action())) {
            set(Status.TOOL_READY, state.steps(), state.answers(), null, null, null, null, now); return;
        }
        if (state.status() != Status.NEEDS_INFORMATION && state.status() != Status.INTERRUPTED) throw invalid();
        if (state.status() == Status.INTERRUPTED && !acknowledgeUnknown || state.status() == Status.NEEDS_INFORMATION && StringUtils.isBlank(answer)
                || answer != null && answer.length() > MAX_MESSAGE_LENGTH) throw invalid();
        var answers = new ArrayList<>(state.answers()); if (StringUtils.isNotBlank(answer)) answers.add(answer);
        set(Status.READY, state.steps(), answers, null, null, null, null, now);
    }
    /** 授权、时限或版本失效后终止循环，不自动扩大范围。 */
    public void stop(Status status, String reason, Instant now) {
        if (status != Status.FAILED && status != Status.INTERRUPTED && status != Status.CANCELLED && status != Status.LIMIT_REACHED) throw invalid();
        set(status, state.steps(), state.answers(), reason, null, state.childId(), state.childAction(), now);
    }
    /** 用户命令及工作线程均使用乐观版本防止迟到结算。 */
    public void requireVersion(long expected) { if (state.version() != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Expense agent version changed"); }
    public Context context() { return context; }
    public State state() { return state; }
    public Step last() { return state.steps().get(state.steps().size() - 1); }
    public boolean active() { return !List.of(Status.COMPLETED, Status.CANCELLED, Status.FAILED, Status.LIMIT_REACHED).contains(state.status()); }
    private void require(Status status) { if (state.status() != status) throw invalid(); }
    private void requireLease(Status status, Instant now) { require(status); if (state.leaseUntil() == null || !state.leaseUntil().isAfter(now)) throw invalid(); }
    private void replace(Step step, Status status, String message, Instant lease, UUID child, Action action, Instant now) {
        var steps = new ArrayList<>(state.steps()); steps.set(steps.size() - 1, step); set(status, steps, state.answers(), message, lease, child, action, now);
    }
    private void set(Status status, List<Step> steps, List<String> answers, String message, Instant lease, UUID child, Action action, Instant now) {
        state = new State(state.version() + 1, status, steps, answers, message, lease, child, action, now);
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_ACTION", "Expense agent action does not match the authorized state"); }
    /**
     * 租户与登录引用留在服务器，模型只看到授权的业务投影。
     * @author owlzhangfq@gmail.com
     */
    public record Context(UUID id, UUID taskId, UUID reportId, String tenantId, String ownerId, String goal,
            long applicationVersion, long financialVersion, Scope scope, String targetDigest, Instant createdAt, Instant deadline) { }
    /**
     * 工具引用来自本人的显式选择，费用修改与模型子任务仍需单独确认。
     * @author owlzhangfq@gmail.com
     */
    public record Scope(List<Integer> policyLineNos, List<UUID> invoiceIds, List<UUID> precheckIds, int maxSteps) {
        public Scope {
            if (policyLineNos == null || invoiceIds == null || precheckIds == null || policyLineNos.size() > MAX_TOOL_REFERENCES || invoiceIds.size() > MAX_TOOL_REFERENCES
                    || precheckIds.size() > MAX_PRECHECK_REFERENCES || maxSteps < 1 || maxSteps > MAX_STEPS
                    || policyLineNos.stream().anyMatch(value -> value == null || value < 1 || value > io.agentflow.expense.ExpenseContent.MAX_LINES)
                    || invoiceIds.stream().anyMatch(Objects::isNull) || precheckIds.stream().anyMatch(Objects::isNull)
                    || policyLineNos.stream().distinct().count() != policyLineNos.size() || invoiceIds.stream().distinct().count() != invoiceIds.size()
                    || precheckIds.stream().distinct().count() != precheckIds.size()) throw invalid();
            policyLineNos = List.copyOf(policyLineNos); invoiceIds = List.copyOf(invoiceIds); precheckIds = List.copyOf(precheckIds);
        }
    }
    /**
     * 固定工具集合没有审批、支付或任意脚本能力。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { EXPENSE, INVOICE, POLICY, PRECHECK_RESULT, EXTRACT_INVOICE, DRAFT, ASK_USER, FINISH }
    /**
     * 决策只有业务动作和最小参数，不接受调用地址或身份覆盖。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Decision(Action action, UUID referenceId, Integer lineNo, String message) {
        /** 封闭参数和长度在模型进入状态机时检查。 */
        public void requireAllowed(Scope scope) {
            if (action == null || StringUtils.isBlank(message) || message.length() > MAX_MESSAGE_LENGTH) throw invalid();
            boolean allowed = switch (action) {
                case INVOICE, EXTRACT_INVOICE -> referenceId != null && scope.invoiceIds().contains(referenceId) && lineNo == null;
                case POLICY -> lineNo != null && scope.policyLineNos().contains(lineNo) && referenceId == null;
                case PRECHECK_RESULT -> referenceId != null && scope.precheckIds().contains(referenceId) && lineNo == null;
                default -> referenceId == null && lineNo == null;
            };
            if (!allowed) throw invalid();
        }
    }
    /**
     * 网络阶段分别持久，人工节点不会被自动循环跨过。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { READY, MODEL_RUNNING, TOOL_READY, TOOL_RUNNING, NEEDS_INFORMATION, NEEDS_CONFIRMATION, WAITING_CHILD, COMPLETED, INTERRUPTED, FAILED, CANCELLED, LIMIT_REACHED }
    /**
     * 原模型身份和工具观测共同形成可恢复的决策依据。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Step(UUID id, Instant createdAt, Decision decision, String outcome, String observation) { }
    /**
     * 步数和回答总量有界，历史只能追加和完成原步骤。
     * @author owlzhangfq@gmail.com
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record State(long version, Status status, List<Step> steps, List<String> answers, String message,
            Instant leaseUntil, UUID childId, Action childAction, Instant updatedAt) {
        public State {
            if (version < 1 || status == null || steps == null || steps.size() > MAX_STEPS || answers == null || answers.size() > MAX_STEPS) throw invalid();
            steps = List.copyOf(steps); answers = List.copyOf(answers);
        }
    }
}
