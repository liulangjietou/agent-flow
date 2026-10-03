package io.agentflow.notification;

import io.agentflow.finance.VoucherReversalCheck;
import io.agentflow.finance.VoucherReversalPort;
import java.util.Optional;
import java.util.UUID;

/**
 * 外部冲销的只读核对与人工登记分别通知，不推断退款或资源恢复。
 * @author owlzhangfq@gmail.com
 */
public enum ReversalCheckNotice {
    UNAVAILABLE(InboxMessage.Kind.REVERSAL_CHECK_ATTENTION, "原外部冲销核对暂不可用，尚未形成可登记结论，请在原申请核对处理。"),
    SOURCE_CHANGED(InboxMessage.Kind.REVERSAL_CHECK_ATTENTION, "原凭证依据或核对资格发生变化，本次外部冲销核对已停止，请核对原记录。"),
    UNRESOLVED(InboxMessage.Kind.REVERSAL_CHECK_ATTENTION, "本次查询尚未核清完整反向凭证，不能据此登记或恢复业务，请核对原记录。"),
    RECORDED(InboxMessage.Kind.REVERSAL_CHECK_RESULT, "原外部冲销核对已由财务明确登记；登记不表示资金已退款，也不自动恢复预算或业务资源。");

    private static final String PREFIX = "reversal-check:";
    private final InboxMessage.Kind kind;
    private final String content;
    ReversalCheckNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.REVERSAL_CHECK_RESULT ? "外部冲销登记结果" : "外部冲销核对需处理"; }
    /** 完整查询回执仍是待确认依据，未登记时不发送业务完成消息。 */
    public static Optional<ReversalCheckNotice> from(VoucherReversalCheck value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> UNAVAILABLE;
            case VOIDED -> SOURCE_CHANGED;
            case CHECKED -> value.receipt().status() == VoucherReversalPort.Status.UNRESOLVED ? UNRESOLVED : null;
            case RECORDED -> RECORDED;
            default -> null;
        });
    }
    /** 每条消息固定原核对身份，不跟随该凭证后来的新查询。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }
    /** 拒绝其他业务键、非规范 UUID 和未知事实。 */
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
     * 消息来源不是财务办理授权。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID checkId, ReversalCheckNotice notice) { }
}
