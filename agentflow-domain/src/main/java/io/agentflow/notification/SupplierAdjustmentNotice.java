package io.agentflow.notification;

import io.agentflow.procurement.SupplierPayableAdjustmentOperation;
import io.agentflow.procurement.SupplierAdjustmentPreparation;
import java.util.Optional;
import java.util.UUID;

/**
 * 每次原应付调整按实际事实去重，ERP 成功、本地完成与安全结束分别表达。
 * @author owlzhangfq@gmail.com
 */
public enum SupplierAdjustmentNotice {
    PREPARATION_RETRY("应付调整依据读取暂不可用，原登记等待重读。"),
    PREPARATION_BLOCKED("应付调整依据未通过，本次尚未登记 ERP 调整。"),
    PREPARATION_VOIDED("原准备依据已变化，本次准备已停止。"),
    EXECUTION_RETRY("发送前复核暂不可用，原指令等待重读。"),
    UNKNOWN("ERP 结果暂不明确，继续核对原编号。"),
    NOT_FOUND("ERP 暂未查到原调整，不代表可以另建调整。"),
    RECONCILING("原 ERP 调整事实存在矛盾，需要人工核对。"),
    REJECTED("ERP 明确拒绝本次调整，安全结束仍需独立处理。"),
    VOIDED("本次调整发送已停止，尚未证明安全结束。"),
    ERP_ADJUSTED("ERP 已确认调整，当时本地账务尚未完成。"),
    COMPLETED("原 ERP 调整及本地账务均已记录完成。"),
    RETIRED("本次应付调整已安全结束，原银行已付事实保持。");
    private static final String PREFIX = "supplier-adjustment:";
    private final String content;
    SupplierAdjustmentNotice(String content) { this.content = content; }
    public String content() { return content; }
    public InboxMessage.Kind kind() { return this == ERP_ADJUSTED || this == COMPLETED || this == RETIRED ? InboxMessage.Kind.SUPPLIER_ADJUSTMENT_RESULT : InboxMessage.Kind.SUPPLIER_ADJUSTMENT_ATTENTION; }
    public String title() { return kind() == InboxMessage.Kind.SUPPLIER_ADJUSTMENT_RESULT ? "供应商应付调整结果更新" : "供应商应付调整需核对"; }
    /** 准备排队和正常读取不生成异常，真实持久原因才触发通知。 */
    public static Optional<SupplierAdjustmentNotice> from(SupplierAdjustmentPreparation value) {
        return Optional.ofNullable(switch (value.status()) {
            case QUEUED -> value.issue() == null ? null : PREPARATION_RETRY;
            case BLOCKED -> PREPARATION_BLOCKED;
            case VOIDED -> PREPARATION_VOIDED;
            default -> null;
        });
    }
    /** 正常等待和主动查询不等于失联，ERP 成功本身不能证明本地已完成。 */
    public static Optional<SupplierAdjustmentNotice> from(SupplierPayableAdjustmentOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case QUEUED -> value.failure() == null ? null : EXECUTION_RETRY;
            case UNKNOWN -> value.failure() == null || value.failure() == SupplierPayableAdjustmentOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case REJECTED -> REJECTED;
            case VOIDED -> VOIDED;
            case ADJUSTED -> ERP_ADJUSTED;
            default -> null;
        });
    }
    /** 自动退避、原号重查只通知同类事实一次，另一次明确准备拥有独立编号。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }
    /** 拒绝非规范编号和未知事实，不从客户端参数选择原应付调整。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        var parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 2) return Optional.empty();
        try { var id = UUID.fromString(parts[0]); return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty(); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 消息固定原准备及执行编号，事实种类不提供财务授权。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID adjustmentId, SupplierAdjustmentNotice notice) { }
}
