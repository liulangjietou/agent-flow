package io.agentflow.notification;

import io.agentflow.procurement.SupplierPayableHoldOperation;
import io.agentflow.procurement.SupplierPayableReview;
import java.util.Optional;
import java.util.UUID;

/**
 * 原应付复核、ERP 预留和安全结束分别通知，不把预留当成银行付款或应付结算。
 * @author owlzhangfq@gmail.com
 */
public enum SupplierPayableNotice {
    REVIEW_UNAVAILABLE("本次应付复核暂不可用，尚未形成新的财务授权。"),
    REVIEW_BLOCKED("本次应付依据未通过复核，请核对原采购与账户条件；尚未登记预留。"),
    REVIEW_SOURCE_CHANGED("原批准或办理条件已变化，本次应付复核已停止。"),
    REVIEW_INTERRUPTED("本次应付读取已中断，正在恢复原请求；不表示财务授权或预留完成。"),
    UNKNOWN("原 ERP 应付预留结果暂不明确，需继续按原授权查询；不能据此另起预留。"),
    NOT_FOUND("本次查询未找到原应付预留；查无不能证明预留从未发生。"),
    HELD("原 ERP 应付已收到预留回执，银行付款与应付结算仍需分别核对。"),
    REJECTED("原 ERP 已明确拒绝本次预留，请核对原授权；这不是银行付款结果。"),
    RECONCILING("原应付预留回执存在矛盾，原观察与冲突依据分别保留，需要继续核对。"),
    EXPIRED("原应付预留发送窗口已到期，未继续发送；不据此推断其他操作结果。"),
    VOIDED("原批准或财务资格已变化，尚未发送的原应付预留已停止。"),
    RETIRED("财务已依据原预留的安全证明结束本次授权，后续办理须重新复核并授权。");

    private static final String PREFIX = "supplier-payable:";
    private final String content;
    SupplierPayableNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == HELD || this == REJECTED || this == RETIRED ? InboxMessage.Kind.SUPPLIER_PAYABLE_RESULT : InboxMessage.Kind.SUPPLIER_PAYABLE_ATTENTION; }
    public String title() { return kind() == InboxMessage.Kind.SUPPLIER_PAYABLE_RESULT ? "供应商应付处理结果" : "供应商应付需核对"; }
    public SourceType sourceType() { return name().startsWith("REVIEW_") ? SourceType.REVIEW : SourceType.OPERATION; }

    /** 只读中断与最终失败保留各自事实，正常等待和可供授权的证据不冒充预留结果。 */
    public static Optional<SupplierPayableNotice> from(SupplierPayableReview value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> REVIEW_UNAVAILABLE;
            case BLOCKED -> REVIEW_BLOCKED;
            case VOIDED -> REVIEW_SOURCE_CHANGED;
            case QUEUED -> value.issue() == SupplierPayableReview.Issue.LEASE_EXPIRED ? REVIEW_INTERRUPTED : null;
            default -> null;
        });
    }
    /** 正常受理和主动查询保持安静，具名安全结束另从真实决定生成。 */
    public static Optional<SupplierPayableNotice> from(SupplierPayableHoldOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case HELD -> HELD;
            case REJECTED -> REJECTED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> value.failure() == SupplierPayableHoldOperation.Failure.FINANCE_RETIRED ? null : VOIDED;
            case UNKNOWN -> value.failure() == null || value.failure() == SupplierPayableHoldOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            default -> null;
        });
    }
    /** 同一原编号同类事实只通知一次，重新读取或授权使用独立编号。 */
    public String eventKey(UUID id) { return PREFIX + sourceType() + ":" + id + ":" + name(); }
    /** 复核、预留和事实须同时匹配，拒绝非规范 UUID 与跨来源事实。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 3) return Optional.empty();
        try {
            var type = SourceType.valueOf(parts[0]); var id = UUID.fromString(parts[1]); var notice = valueOf(parts[2]);
            return type == notice.sourceType() && id.toString().equals(parts[1]) ? Optional.of(new Source(type, id, notice)) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 闭集定位原复核或原预留，不提供操作许可。
     * @author owlzhangfq@gmail.com
     */
    public enum SourceType { REVIEW, OPERATION }
    /**
     * 原编号与发生事实共同限定消息身份。
     * @author owlzhangfq@gmail.com
     */
    public record Source(SourceType type, UUID id, SupplierPayableNotice notice) { }
}
