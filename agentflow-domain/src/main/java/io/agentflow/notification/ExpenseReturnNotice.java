package io.agentflow.notification;

import io.agentflow.expense.ExpensePaymentReturnCheck;
import io.agentflow.finance.ExpensePaymentReturnPort;
import java.util.Optional;
import java.util.UUID;

/**
 * 银行查询、退回疑点与独立登记分别通知，不推断 预算或其他资源已经调整。
 * @author owlzhangfq@gmail.com
 */
public enum ExpenseReturnNotice {
    UNAVAILABLE("原报销退回查询暂不可用，尚未形成可登记结论。"),
    SOURCE_CHANGED("原来源或核对资格已变化，本次退回查询已停止。"),
    UNRESOLVED("本次查询尚未核清原付款及实际入款，需要继续核对。"),
    RETURN_REVIEW("本次原件显示银行资金退回，等待财务明确核对和登记。"),
    RECORDED("本次退回核对已由财务明确登记，退回资金与后续资源调整仍需分别核对。");
    private static final String PREFIX = "expense-return:";
    private final String content;
    ExpenseReturnNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == RECORDED ? InboxMessage.Kind.EXPENSE_RETURN_RESULT : InboxMessage.Kind.EXPENSE_RETURN_ATTENTION; }
    public String title() { return this == RECORDED ? "报销退回核对结果" : "报销退回需核对"; }
    /** 未见退回的正常查询保持安静，查询完成不代表财务已登记。 */
    public static Optional<ExpenseReturnNotice> from(ExpensePaymentReturnCheck value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> UNAVAILABLE;
            case VOIDED -> SOURCE_CHANGED;
            case CHECKED -> switch (value.receipt().status()) {
                case UNRESOLVED -> UNRESOLVED;
                case PARTIALLY_RETURNED, RETURNED -> RETURN_REVIEW;
                default -> null;
            };
            case RESOLVED -> RECORDED;
            default -> null;
        });
    }
    /** 登记消费同一份原件，旧待核对消息仍可读取原查询及其真实决定。 */
    public boolean presentIn(ExpensePaymentReturnCheck value) {
        return from(value).filter(fact -> fact == this).isPresent() || this == RETURN_REVIEW && value.status() == ExpensePaymentReturnCheck.Status.RESOLVED
                && (value.receipt().status() == ExpensePaymentReturnPort.Status.PARTIALLY_RETURNED || value.receipt().status() == ExpensePaymentReturnPort.Status.RETURNED);
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
    public record Source(UUID checkId, ExpenseReturnNotice notice) { }
}
