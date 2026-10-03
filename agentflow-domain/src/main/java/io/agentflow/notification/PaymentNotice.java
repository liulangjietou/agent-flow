package io.agentflow.notification;

import io.agentflow.finance.PaymentOperation;
import io.agentflow.finance.PaymentExecutionRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * 员工付款事实的最小通知语义；同一原付款的同类事实只通知一次，不随查询版本增长。
 * @author owlzhangfq@gmail.com
 */
public enum PaymentNotice {
    SUCCEEDED(InboxMessage.Kind.PAYMENT_RESULT, "原付款已收到成功回执，请查看当前付款记录。"),
    FAILED(InboxMessage.Kind.PAYMENT_RESULT, "原付款已收到未成功回执，请查看当前付款记录。"),
    REVERSED(InboxMessage.Kind.PAYMENT_RESULT, "原付款已收到资金退回回执，请查看当前付款记录。"),
    UNKNOWN(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款结果暂不明确，请核对原交易；不能据此重新付款。"),
    NOT_FOUND(InboxMessage.Kind.PAYMENT_ATTENTION, "原资金交易查询返回查无，请核对原记录；系统未自动重新付款。"),
    RECONCILING(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款回执存在冲突，请查看原记录并按权限核对。"),
    EXPIRED(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款授权已过期，尚未开始的新发送已停止，请核对原记录。"),
    SOURCE_CHANGED(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款依据发生变化，尚未开始的新发送已停止，请核对原记录。"),
    ACCOUNT_CHANGED(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款账户依据未通过复核，尚未开始的新发送已停止，请核对原记录。"),
    CHECK_UNAVAILABLE(InboxMessage.Kind.PAYMENT_ATTENTION, "原付款发送前的依据检查暂不可用，请查看原记录；这不表示银行付款失败。");

    private static final String KEY_PREFIX = "employee-payment:";
    private final InboxMessage.Kind kind;
    private final String content;

    PaymentNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.PAYMENT_RESULT ? "付款结果更新" : "付款执行需核对"; }

    /** 处理中和主动复查不制造异常；成功、失败和退回只来自领域已确认的终态。 */
    public static Optional<PaymentNotice> from(PaymentOperation operation) {
        PaymentNotice notice = switch (operation.status()) {
            case SUCCEEDED -> SUCCEEDED;
            case FAILED -> FAILED;
            case REVERSED -> REVERSED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> switch (operation.failure()) {
                case SOURCE_CHANGED -> SOURCE_CHANGED;
                case ACCOUNT_CHANGED -> ACCOUNT_CHANGED;
                default -> null;
            };
            case UNKNOWN -> operation.failure() == null || operation.failure() == PaymentOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            case QUEUED -> operation.failure() == null ? null : CHECK_UNAVAILABLE;
            default -> null;
        };
        return Optional.ofNullable(notice);
    }

    /** 原出纳选择仍在复查，不能把检查异常解释为银行资金结果。 */
    public static Optional<PaymentNotice> from(PaymentExecutionRequest request) {
        PaymentNotice notice = switch (request.status()) {
            case BLOCKED -> ACCOUNT_CHANGED;
            case VOIDED -> SOURCE_CHANGED;
            case EXPIRED -> EXPIRED;
            case QUEUED -> request.failure() == null ? null : CHECK_UNAVAILABLE;
            default -> null;
        };
        return Optional.ofNullable(notice);
    }

    /** 原事件键既是去重身份也是受控来源定位；不携带金额、账户或外部回执正文。 */
    public String eventKey(UUID paymentId) { return KEY_PREFIX + paymentId + ":" + name(); }

    /** 历史通知只接受此版本写入的规范原付款标识和事实，不能把其他消息当作付款入口。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) return Optional.empty();
        String[] parts = key.substring(KEY_PREFIX.length()).split(":", -1);
        if (parts.length != 2) return Optional.empty();
        try {
            UUID id = UUID.fromString(parts[0]);
            return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /**
     * 只定位原付款，不代表调用人获得读取或执行权限。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID paymentId, PaymentNotice notice) { }
}
