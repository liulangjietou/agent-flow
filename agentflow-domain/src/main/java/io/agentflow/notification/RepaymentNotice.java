package io.agentflow.notification;

import io.agentflow.expense.AdvanceRepaymentCheck;
import java.util.Optional;
import java.util.UUID;

/**
 * 还款原件、明确登记和触发复核分别通知，不推断后续资金裁决。
 * @author owlzhangfq@gmail.com
 */
public enum RepaymentNotice {
    UNAVAILABLE("原还款查询暂不可用，尚未取得可登记的收款依据。"),
    SOURCE_CHANGED("原放款来源或核对资格已变化，本次还款查询已停止。"),
    NOT_FOUND("本次查询未找到匹配的收款原件，不能据此认定已经还款。"),
    PENDING("本次原件尚未完成收款核对，尚不能登记还款。"),
    REVERSED("本次原件显示收款已撤销，借款登记及后续处理需要分别核对。"),
    REVIEW_REQUIRED("本次查询与原还款依据不一致，已要求复核；原还款记录保留，查询不会自行改变欠款。"),
    RECORDED("本次收款原件已由财务明确登记还款；后续复核与既有占用分别保留。");
    private static final String PREFIX = "repayment:";
    private final String content;
    RepaymentNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == RECORDED ? InboxMessage.Kind.REPAYMENT_RESULT : InboxMessage.Kind.REPAYMENT_ATTENTION; }
    public String title() { return this == RECORDED ? "借款还款登记结果" : "借款还款需核对"; }
    /** 正常确认的查询保持安静；原还款发生冲突时优先通知真实复核事实。 */
    public static Optional<RepaymentNotice> from(AdvanceRepaymentCheck value) {
        if (value.reviewRepaymentId() != null) return Optional.of(REVIEW_REQUIRED);
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> UNAVAILABLE;
            case VOIDED -> SOURCE_CHANGED;
            case RECORDED -> RECORDED;
            case CHECKED -> switch (value.receipt().status()) {
                case NOT_FOUND -> NOT_FOUND;
                case PENDING -> PENDING;
                case REVERSED -> REVERSED;
                case CONFIRMED -> null;
            };
            default -> null;
        });
    }
    /** 通知只代表这次查询或登记，不能借用另一查询或后续复核的状态。 */
    public boolean presentIn(AdvanceRepaymentCheck value) { return from(value).filter(fact -> fact == this).isPresent(); }
    /** 原查询与事实共同去重，另一次明确查询具有独立编号。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }
    /** 不接受另一业务键、非规范编号或未知事实。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 2) return Optional.empty();
        try { var id = UUID.fromString(parts[0]); return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty(); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 原查询标识不提供财务写入许可。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID checkId, RepaymentNotice notice) { }
}
