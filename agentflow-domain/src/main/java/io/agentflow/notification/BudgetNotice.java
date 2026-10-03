package io.agentflow.notification;

import io.agentflow.finance.BudgetObservation;
import io.agentflow.finance.BudgetOperation;
import java.util.Optional;
import java.util.UUID;

/**
 * 原预算命令的最小事实；单笔操作确认不代表当前占用、付款或结算已经完成。
 * @author owlzhangfq@gmail.com
 */
public enum BudgetNotice {
    UNKNOWN(InboxMessage.Kind.BUDGET_ATTENTION, "原预算操作结果暂不明确，系统将按原编号查询，请核对原记录。"),
    NOT_FOUND(InboxMessage.Kind.BUDGET_ATTENTION, "权威查询确认原预算操作查无，系统将保留原命令和编号重试，请核对原记录。"),
    REJECTED(InboxMessage.Kind.BUDGET_RESULT, "预算系统已明确拒绝原操作，请在原申请核对处理；其他预算操作和付款结果需分别确认。"),
    APPLIED(InboxMessage.Kind.BUDGET_RESULT, "原预算操作已收到确认回执，请核对原记录；当前预算占用、付款和结算进度需分别确认。");

    private static final String PREFIX = "budget:";
    private final InboxMessage.Kind kind;
    private final String content;
    BudgetNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.BUDGET_RESULT ? "预算操作结果更新" : "预算操作需核对"; }

    /** 正常排队、发送和外部受理中不制造异常；查询查无也不等同于拒绝。 */
    public static Optional<BudgetNotice> from(BudgetOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case APPLIED -> APPLIED;
            case REJECTED -> REJECTED;
            case UNKNOWN -> value.failure() == null ? null : UNKNOWN;
            case QUEUED -> value.observation() != null && value.observation().status() == BudgetObservation.Status.NOT_FOUND ? NOT_FOUND : null;
            default -> null;
        });
    }

    /** 新的业务命令用新编号，同一命令反复查询只通知一次同类事实。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }

    /** 严格解析原编号及闭集事实，拒绝宽松 UUID 别名或其他业务键。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        String[] parts = key.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) return Optional.empty();
        try {
            UUID id = UUID.fromString(parts[0]);
            return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /**
     * 通知来源标识不授予任何业务权限。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID operationId, BudgetNotice notice) { }
}
