package io.agentflow.notification;

import io.agentflow.budget.BudgetAdjustmentOperation;
import io.agentflow.budget.BudgetAdjustmentReview;
import java.util.Optional;
import java.util.UUID;

/**
 * 台账复核、原预算指令结果与安全结束分别通知，不推断其他资金或预算操作完成。
 * @author owlzhangfq@gmail.com
 */
public enum BudgetAdjustmentNotice {
    REVIEW_UNAVAILABLE("本次预算台账复核暂不可用，尚未形成新的调整授权。"),
    REVIEW_BLOCKED("本次预算台账条件不满足，财务需要核对原依据；尚未执行预算调整。"),
    REVIEW_SOURCE_CHANGED("原来源或办理条件已变化，本次台账复核已停止。"),
    UNKNOWN("原预算调整指令结果暂不明确，需继续按原编号查询。"),
    NOT_FOUND("本次查询未找到原预算指令；查无不能证明预算从未调整。"),
    REJECTED("原系统已明确拒绝整条预算调整指令，请在原申请核对处理。"),
    APPLIED("原预算调整指令已收到完整生效回执，其他预算操作仍需分别核对。"),
    RECONCILING("原预算调整回执存在矛盾，原结果与冲突依据分别保留，需要继续核对。"),
    EXPIRED("原预算调整授权窗口已到期，原指令不能继续发送；不据此推断其他操作结果。"),
    VOIDED("原预算调整指令发送已停止，请核对原记录和当前办理资格。"),
    RETIRED("财务已依据原指令的无副作用证明安全结束本次操作，后续调整须另行授权。");
    private static final String PREFIX = "budget-adjustment:";
    private final String content;
    BudgetAdjustmentNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == APPLIED || this == REJECTED || this == RETIRED ? InboxMessage.Kind.BUDGET_ADJUSTMENT_RESULT : InboxMessage.Kind.BUDGET_ADJUSTMENT_ATTENTION; }
    public String title() { return kind() == InboxMessage.Kind.BUDGET_ADJUSTMENT_RESULT ? "预算调整结果" : "预算调整需核对"; }
    public SourceType sourceType() { return this == REVIEW_UNAVAILABLE || this == REVIEW_BLOCKED || this == REVIEW_SOURCE_CHANGED ? SourceType.REVIEW : SourceType.OPERATION; }
    /** 正常台账读取与可供授权的证据不等同于预算生效。 */
    public static Optional<BudgetAdjustmentNotice> from(BudgetAdjustmentReview value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> REVIEW_UNAVAILABLE;
            case BLOCKED -> REVIEW_BLOCKED;
            case VOIDED -> REVIEW_SOURCE_CHANGED;
            default -> null;
        });
    }
    /** 外部受理与主动重新查询保持安静，未知异常只按原指令恢复。 */
    public static Optional<BudgetAdjustmentNotice> from(BudgetAdjustmentOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case APPLIED -> APPLIED;
            case REJECTED -> REJECTED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> VOIDED;
            case UNKNOWN -> value.failure() == null || value.failure() == BudgetAdjustmentOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            default -> null;
        });
    }
    /** 同一来源同类事实只通知一次，另一次复核或授权使用独立编号。 */
    public String eventKey(UUID id) { return PREFIX + sourceType() + ":" + id + ":" + name(); }
    /** 不允许把复核、指令或其他业务的编号互换。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 3) return Optional.empty();
        try {
            var type = SourceType.valueOf(parts[0]); var id = UUID.fromString(parts[1]); var notice = valueOf(parts[2]);
            return type == notice.sourceType() && id.toString().equals(parts[1]) ? Optional.of(new Source(type, id, notice)) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 来源闭集只定位原财务事实，不提供办理权限。
     * @author owlzhangfq@gmail.com
     */
    public enum SourceType { REVIEW, OPERATION }
    /**
     * 原复核或原指令标识与事实种类共同构成消息身份。
     * @author owlzhangfq@gmail.com
     */
    public record Source(SourceType type, UUID id, BudgetAdjustmentNotice notice) { }
}
