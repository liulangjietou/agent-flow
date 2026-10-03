package io.agentflow.notification;

import io.agentflow.procurement.SupplierPaymentReturnCheck;
import io.agentflow.procurement.SupplierPaymentReturnPort;
import java.util.Optional;
import java.util.UUID;

/**
 * 银行查询、回款疑点与独立登记分别通知，不推断 ERP 或本地账务已经调整。
 * @author owlzhangfq@gmail.com
 */
public enum SupplierReturnNotice {
    UNAVAILABLE("原供应商回款查询暂不可用，尚未形成可登记结论。"),
    SOURCE_CHANGED("原来源或核对资格已变化，本次回款查询已停止。"),
    UNRESOLVED("本次查询尚未核清原付款及实际入款，需要继续核对。"),
    RETURN_REVIEW("本次原件显示银行资金退回，等待财务明确核对和登记。"),
    RECORDED("本次回款核对已由财务明确登记，ERP 调整与本地账务完成仍需分别核对。");
    private static final String PREFIX = "supplier-return:";
    private final String content;
    SupplierReturnNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == RECORDED ? InboxMessage.Kind.SUPPLIER_RETURN_RESULT : InboxMessage.Kind.SUPPLIER_RETURN_ATTENTION; }
    public String title() { return this == RECORDED ? "供应商回款核对结果" : "供应商回款需核对"; }
    /** 未见退回的正常查询保持安静，查询完成不代表财务已登记。 */
    public static Optional<SupplierReturnNotice> from(SupplierPaymentReturnCheck value) {
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
    public boolean presentIn(SupplierPaymentReturnCheck value) {
        return from(value).filter(fact -> fact == this).isPresent() || this == RETURN_REVIEW && value.status() == SupplierPaymentReturnCheck.Status.RESOLVED
                && (value.receipt().status() == SupplierPaymentReturnPort.Status.PARTIALLY_RETURNED || value.receipt().status() == SupplierPaymentReturnPort.Status.RETURNED);
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
    public record Source(UUID checkId, SupplierReturnNotice notice) { }
}
