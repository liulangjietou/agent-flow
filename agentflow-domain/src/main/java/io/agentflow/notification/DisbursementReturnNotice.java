package io.agentflow.notification;

import io.agentflow.expense.AdvanceDisbursementReturnCheck;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import java.util.Optional;
import java.util.UUID;

/**
 * 银行查询、退回疑点与独立登记分别通知，不推断预算或其他资源已经调整。
 * @author owlzhangfq@gmail.com
 */
public enum DisbursementReturnNotice {
    UNAVAILABLE("原借款放款退回查询暂不可用，尚未形成可登记结论。"),
    SOURCE_CHANGED("原来源或核对资格已变化，本次退回查询已停止。"),
    UNRESOLVED("本次查询尚未核清原付款及实际入款，需要继续核对。"),
    RETURN_REVIEW("本次原件显示银行资金退回，等待财务明确核对和登记。"),
    REVIEW_REQUIRED("本次原件与既有放款或退回依据不一致，已要求重新核对；历史资金记录保持不变。"),
    RESOLVED("本次原放款核对已由财务明确裁决，借款余额按本次决定记录；其他还款复核和占用仍分别保留。");
    private static final String PREFIX = "disbursement-return:";
    private final String content;
    DisbursementReturnNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == RESOLVED ? InboxMessage.Kind.DISBURSEMENT_RETURN_RESULT : InboxMessage.Kind.DISBURSEMENT_RETURN_ATTENTION; }
    public String title() { return this == RESOLVED ? "借款放款退回核对结果" : "借款放款退回需核对"; }
    /** 与历史一致的正常复查保持安静，矛盾结果与待登记退回分别通知。 */
    public static Optional<DisbursementReturnNotice> from(AdvanceDisbursementReturnCheck value) {
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
    public boolean presentIn(AdvanceDisbursementReturnCheck value) {
        if (from(value).filter(fact -> fact == this).isPresent()) return true;
        if (value.status() != AdvanceDisbursementReturnCheck.Status.RESOLVED) return false;
        return this == RETURN_REVIEW && !Boolean.FALSE.equals(value.reviewRequired())
                && (value.receipt().status() == AdvanceDisbursementReturnPort.Status.PARTIALLY_RETURNED || value.receipt().status() == AdvanceDisbursementReturnPort.Status.RETURNED)
                || this == REVIEW_REQUIRED && Boolean.TRUE.equals(value.reviewRequired()) && value.receipt().status() == AdvanceDisbursementReturnPort.Status.CONFIRMED;
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
    public record Source(UUID checkId, DisbursementReturnNotice notice) { }
}
