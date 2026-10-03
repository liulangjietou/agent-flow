package io.agentflow.notification;

import io.agentflow.expense.AdvanceRepaymentReviewCheck;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import java.util.Optional;
import java.util.UUID;

/**
 * 原还款复核、历史矛盾与独立裁决分别通知，不推断其他资源已经调整。
 * @author owlzhangfq@gmail.com
 */
public enum RepaymentReviewNotice {
    UNAVAILABLE("原还款复核查询暂不可用，尚未形成可裁决结论。"),
    SOURCE_CHANGED("原来源或核对资格已变化，本次复核查询已停止。"),
    UNRESOLVED("本次原还款及实际退回尚未核清，需要继续核对。"),
    RETURN_REVIEW("本次原件显示还款资金退回，等待财务明确复核和裁决。"),
    REVIEW_REQUIRED("本次原件与已确认的还款或退回依据不一致，已要求重新复核；历史登记保持不变。"),
    RESOLVED("本次原还款已由财务明确裁决，借款余额按原决定记录；原放款和其他占用仍分别保留。");
    private static final String PREFIX = "repayment-review:";
    private final String content;
    RepaymentReviewNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == RESOLVED ? InboxMessage.Kind.REPAYMENT_REVIEW_RESULT : InboxMessage.Kind.REPAYMENT_REVIEW_ATTENTION; }
    public String title() { return this == RESOLVED ? "借款还款复核结果" : "借款还款需复核"; }
    /** 与历史一致的正常复查保持安静，矛盾结果与待登记退回分别通知。 */
    public static Optional<RepaymentReviewNotice> from(AdvanceRepaymentReviewCheck value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> UNAVAILABLE;
            case VOIDED -> SOURCE_CHANGED;
            case CHECKED -> switch (value.receipt().status()) {
                case UNRESOLVED -> UNRESOLVED;
                case PARTIALLY_RETURNED, RETURNED -> Boolean.FALSE.equals(value.reviewRequired()) ? null : RETURN_REVIEW;
                case CONFIRMED -> Boolean.TRUE.equals(value.reviewRequired()) ? REVIEW_REQUIRED : null;
            };
            case RESOLVED -> RESOLVED;
            default -> null;
        });
    }
    /** 登记消费同一份原件，旧待核对消息仍可读取原查询及其真实决定。 */
    public boolean presentIn(AdvanceRepaymentReviewCheck value) {
        if (from(value).filter(fact -> fact == this).isPresent()) return true;
        if (value.status() != AdvanceRepaymentReviewCheck.Status.RESOLVED) return false;
        return this == RETURN_REVIEW && !Boolean.FALSE.equals(value.reviewRequired())
                && (value.receipt().status() == AdvanceRepaymentAdjustmentPort.Status.PARTIALLY_RETURNED || value.receipt().status() == AdvanceRepaymentAdjustmentPort.Status.RETURNED)
                || this == REVIEW_REQUIRED && Boolean.TRUE.equals(value.reviewRequired()) && value.receipt().status() == AdvanceRepaymentAdjustmentPort.Status.CONFIRMED;
    }
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
    public record Source(UUID checkId, RepaymentReviewNotice notice) { }
}
