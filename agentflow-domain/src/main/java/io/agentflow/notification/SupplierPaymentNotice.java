package io.agentflow.notification;

import io.agentflow.procurement.SupplierPaymentExecutionRequest;
import io.agentflow.procurement.SupplierPaymentOperation;
import java.util.Optional;
import java.util.UUID;

/**
 * 供应商原授权与原出纳选择的最小通知事实，新的选择不能替换历史消息的归属。
 * @author owlzhangfq@gmail.com
 */
public enum SupplierPaymentNotice {
    SUCCEEDED(InboxMessage.Kind.SUPPLIER_PAYMENT_RESULT, "原供应商付款已收到成功回执，请查看当前原记录；应付结算仍需单独核对。"),
    FAILED(InboxMessage.Kind.SUPPLIER_PAYMENT_RESULT, "原供应商付款已收到未成功回执，请查看当前原记录。"),
    REVERSED(InboxMessage.Kind.SUPPLIER_PAYMENT_RESULT, "原供应商付款已收到资金退回回执，请查看当前原记录。"),
    UNKNOWN(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款结果暂不明确，请核对原交易；不能据此重新付款。"),
    NOT_FOUND(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商资金交易查询返回查无，请核对原记录；系统未自动重新付款。"),
    RECONCILING(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款回执存在冲突，请查看原记录并按权限核对。"),
    EXPIRED(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款授权已过期，尚未开始的新发送已停止，请核对原记录。"),
    SOURCE_CHANGED(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款依据发生变化，尚未开始的新发送已停止，请核对原记录。"),
    EVIDENCE_CHANGED(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款的预留、应付或账户依据未通过复核，尚未开始的新发送已停止，请核对原记录。"),
    CHECK_UNAVAILABLE(InboxMessage.Kind.SUPPLIER_PAYMENT_ATTENTION, "原供应商付款发送前的依据检查暂不可用，请查看原记录；这不表示银行付款失败。");

    private static final String KEY_PREFIX = "supplier-payment:";
    private final InboxMessage.Kind kind;
    private final String content;
    SupplierPaymentNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.SUPPLIER_PAYMENT_RESULT ? "供应商付款结果更新" : "供应商付款需核对"; }

    /** 已确认银行终态与未知、冲突分开，正常处理中或人工查询不制造异常。 */
    public static Optional<SupplierPaymentNotice> from(SupplierPaymentOperation operation) {
        SupplierPaymentNotice notice = switch (operation.status()) {
            case SUCCEEDED -> SUCCEEDED;
            case FAILED -> FAILED;
            case REVERSED -> REVERSED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> operation.failure() == SupplierPaymentOperation.Failure.SOURCE_CHANGED ? SOURCE_CHANGED : EVIDENCE_CHANGED;
            case UNKNOWN -> operation.failure() == null || operation.failure() == SupplierPaymentOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            case QUEUED -> operation.failure() == null ? null : CHECK_UNAVAILABLE;
            default -> null;
        };
        return Optional.ofNullable(notice);
    }

    /** 登记前检查失败仍无银行命令，通知不能推定资金结果。 */
    public static Optional<SupplierPaymentNotice> from(SupplierPaymentExecutionRequest request) {
        SupplierPaymentNotice notice = switch (request.status()) {
            case BLOCKED -> EVIDENCE_CHANGED;
            case VOIDED -> SOURCE_CHANGED;
            case EXPIRED -> EXPIRED;
            case QUEUED -> request.failure() == null ? null : CHECK_UNAVAILABLE;
            default -> null;
        };
        return Optional.ofNullable(notice);
    }

    /** 同一次选择的登记前检查和付款检查共用去重键，不跨不同原选择合并消息。 */
    public String eventKey(UUID paymentId, UUID executionRequestId) { return KEY_PREFIX + paymentId + ":" + executionRequestId + ":" + name(); }

    /** 只接受规范标识和闭集事实；申请及租户绑定仍由受控来源读取复核。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) return Optional.empty();
        String[] parts = key.substring(KEY_PREFIX.length()).split(":", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            UUID payment = UUID.fromString(parts[0]), request = UUID.fromString(parts[1]);
            return payment.toString().equals(parts[0]) && request.toString().equals(parts[1])
                    ? Optional.of(new Source(payment, request, valueOf(parts[2]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 原授权与原登记请求双重定位，不赋予读取或办理权限。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID paymentId, UUID executionRequestId, SupplierPaymentNotice notice) { }
}
