package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.organization.ApprovalProxyUse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 每轮固定的财务控制输入与独立纸件确认；重提不能沿用上一轮的签收结论。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseSubmissionControl(Input input, long version, Instant submittedAt, PaperReceipt receipt) {
    /** 签收只追加一次，不覆盖原提交事实。 */
    public ExpenseSubmissionControl {
        if (input == null || submittedAt == null || version != (receipt == null ? 1 : 2)
                || receipt != null && (!input.paperReceiptRequired() || receipt.receivedAt().isBefore(submittedAt)
                    || input.stages().get(receipt.nodeId()) != ExpenseProcessPolicy.Stage.RECEIPT)) throw invalid();
    }

    /** 正式提交生成本轮控制事实，预算预检编号只作证据引用。 */
    public static ExpenseSubmissionControl submitted(Input input, Instant now) { return new ExpenseSubmissionControl(input, 1, now, null); }

    /** 当前签收任务人工确认原件；普通审批同意不能替代签收。 */
    public ExpenseSubmissionControl receive(String taskId, String nodeId, String actor, String comment, Instant at) {
        return receive(taskId, nodeId, actor, comment, at, null);
    }

    /** 有期限代理签收同时保留实际人员和当时授权，不替换原节点审批责任。 */
    public ExpenseSubmissionControl receive(String taskId, String nodeId, String actor, String comment, Instant at, ApprovalProxyUse proxyUse) {
        if (!input.paperReceiptRequired() || receipt != null || stage(nodeId) != ExpenseProcessPolicy.Stage.RECEIPT) {
            throw new DomainException("EXPENSE_RECEIPT_NOT_ALLOWED", "Paper receipt is not required or has already been recorded for this round");
        }
        return new ExpenseSubmissionControl(input, 2, submittedAt, new PaperReceipt(taskId, nodeId, actor, comment, at, proxyUse));
    }

    /** 节点必须来自本轮冻结的已发布流程，未知任务没有默认财务职责。 */
    public ExpenseProcessPolicy.Stage stage(String nodeId) {
        var stage = input.stages().get(nodeId);
        if (stage == null) throw new DomainException("EXPENSE_TASK_CONTEXT_CHANGED", "Task is not part of the frozen expense process");
        return stage;
    }

    /** 不需纸件的法人可以放行；需纸件的本轮必须已留下人工确认。 */
    public boolean paperReady() { return !input.paperReceiptRequired() || receipt != null; }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_CONTROL", "Expense submission control or paper receipt is invalid"); }

    /**
     * 绑定本人单据、申请、轮次、正式冻结版本和当时的法人要求。
     * @author owlzhangfq@gmail.com
     */
    public record Input(String tenantId, UUID reportId, UUID applicationId, String employeeId, int roundNo,
            long submittedFinancialVersion, UUID precheckId, LocalDate accountingDate, boolean paperReceiptRequired,
            Map<String, ExpenseProcessPolicy.Stage> stages) {
        /** 固定映射按节点标识排序，重启后的 JSON 编码不能改变原输入。 */
        public Input {
            if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || reportId == null || applicationId == null
                    || StringUtils.isBlank(employeeId) || employeeId.length() > 128 || roundNo < 1 || submittedFinancialVersion < 2
                    || precheckId == null || accountingDate == null || CollectionUtils.isEmpty(stages)
                    || stages.entrySet().stream().anyMatch(item -> StringUtils.isBlank(item.getKey()) || item.getKey().length() > 128 || item.getValue() == null)
                    || stages.values().stream().noneMatch(ExpenseProcessPolicy.Stage::finance)
                    || paperReceiptRequired && !stages.containsValue(ExpenseProcessPolicy.Stage.RECEIPT)) throw invalid();
            stages = Collections.unmodifiableMap(new TreeMap<>(stages));
        }
    }

    /**
     * 可审计的人工原件签收，任务与人员由实时任务授权取得。
     * @author owlzhangfq@gmail.com
     */
    public record PaperReceipt(String taskId, String nodeId, String receivedBy, String comment, Instant receivedAt, ApprovalProxyUse proxyUse) {
        /** 旧签收和原生责任签收不补造代理来源。 */
        public PaperReceipt(String taskId, String nodeId, String receivedBy, String comment, Instant receivedAt) {
            this(taskId, nodeId, receivedBy, comment, receivedAt, null);
        }
        /** 没有任务、操作者、说明和时刻不能确认实物签收。 */
        public PaperReceipt {
            if (StringUtils.isBlank(taskId) || taskId.length() > 128 || StringUtils.isBlank(nodeId) || nodeId.length() > 128
                    || StringUtils.isBlank(receivedBy) || receivedBy.length() > 128 || StringUtils.isBlank(comment) || comment.length() > 2000 || receivedAt == null) throw invalid();
        }
    }
}
