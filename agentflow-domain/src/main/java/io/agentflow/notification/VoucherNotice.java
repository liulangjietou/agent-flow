package io.agentflow.notification;

import io.agentflow.finance.VoucherOperation;
import io.agentflow.finance.VoucherPreparation;
import java.util.Optional;
import java.util.UUID;

/**
 * 原会计准备与过账的最小事实；通知不推断银行到账、结算或归档结果。
 * @author owlzhangfq@gmail.com
 */
public enum VoucherNotice {
    PREPARATION_BLOCKED(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证准备条件未满足，尚未登记过账命令，请核对原记录。"),
    PREPARATION_UNAVAILABLE(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证准备暂不可用，尚未登记过账命令，请核对原记录。"),
    SOURCE_CHANGED(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证依据发生变化，尚未开始的新发送已停止，请核对原记录。"),
    EXPIRED(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证发送期限已过，尚未开始的新发送已停止，请核对原记录。"),
    UNKNOWN(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证过账结果暂不明确，请核对原操作；不能据此重新生成凭证。"),
    NOT_FOUND(InboxMessage.Kind.VOUCHER_ATTENTION, "ERP 查询返回原凭证操作查无，请核对原记录；系统未自动重发。"),
    RECONCILING(InboxMessage.Kind.VOUCHER_ATTENTION, "原凭证回执存在冲突，请查看原记录并按权限核对。"),
    POSTED(InboxMessage.Kind.VOUCHER_RESULT, "原凭证已收到过账回执，请查看当前原记录；资金到账与业务结算仍需分别核对。"),
    FAILED(InboxMessage.Kind.VOUCHER_RESULT, "原凭证已收到过账未通过回执，请核对原记录；这不表示银行付款失败。"),
    REVERSED(InboxMessage.Kind.VOUCHER_RESULT, "原凭证已收到冲销回执，请核对原记录；资金和资源恢复仍需分别确认。");

    private static final String PREFIX = "voucher:";
    private final InboxMessage.Kind kind;
    private final String content;
    VoucherNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.VOUCHER_RESULT ? "凭证结果更新" : "凭证处理需核对"; }

    /** 准备成功、无需金额凭证和正常排队不制造失败或过账结果。 */
    public static Optional<VoucherNotice> from(VoucherPreparation value) {
        return Optional.ofNullable(switch (value.status()) {
            case BLOCKED -> PREPARATION_BLOCKED;
            case UNAVAILABLE -> PREPARATION_UNAVAILABLE;
            case VOIDED -> SOURCE_CHANGED;
            default -> null;
        });
    }

    /** ERP 受理中和显式复查不是异常；租约丢失只能说明原结果未知。 */
    public static Optional<VoucherNotice> from(VoucherOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case POSTED -> POSTED;
            case FAILED -> FAILED;
            case REVERSED -> REVERSED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> SOURCE_CHANGED;
            case UNKNOWN -> value.failure() == null || value.failure() == VoucherOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            default -> null;
        });
    }

    /** 原准备和由其登记的操作共用编号，新的准备尝试始终使用另一个编号。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }

    /** 事件只能解析为规范原编号和闭集事实，不能用宽松 UUID 别名定位业务。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        String[] parts = key.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) return Optional.empty();
        try {
            UUID id = UUID.fromString(parts[0]);
            return id.toString().equals(parts[0]) ? Optional.of(new Source(id, valueOf(parts[1]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /**
     * 来源标识不授予查看或办理权限。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID voucherId, VoucherNotice notice) { }
}
