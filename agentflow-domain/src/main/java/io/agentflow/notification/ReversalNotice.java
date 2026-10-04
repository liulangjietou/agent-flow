package io.agentflow.notification;

import io.agentflow.finance.VoucherReversalOperation;
import io.agentflow.finance.VoucherReversalPreparation;
import java.util.Optional;
import java.util.UUID;

/**
 * 独立冲销的原事实；准备、ERP 写入和安全结束不互相冒充，也不推断银行退款。
 * @author owlzhangfq@gmail.com
 */
public enum ReversalNotice {
    PREPARATION_UNAVAILABLE(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销准备暂不可用，尚未登记本次冲销命令，请核对原记录。"),
    PREPARATION_VOIDED(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销准备依据发生变化，尚未登记本次冲销命令，请核对原记录。"),
    SOURCE_CHANGED(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销依据发生变化，尚未开始的新发送已停止，请核对原记录。"),
    EXPIRED(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销发送依据已过期，尚未开始的新发送已停止，请核对原记录。"),
    UNKNOWN(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销执行结果暂不明确，系统将按原编号查询，请核对原记录。"),
    NOT_FOUND(InboxMessage.Kind.REVERSAL_ATTENTION, "ERP 权威查询返回原冲销查无，系统未自动重发，请按权限核对原记录。"),
    RECONCILING(InboxMessage.Kind.REVERSAL_ATTENTION, "原冲销回执存在冲突，请按权限核对原记录；此前会计事实仍保留。"),
    POSTED(InboxMessage.Kind.REVERSAL_RESULT, "原独立冲销已收到反向凭证过账回执，原凭证及业务状态需分别核对；这不表示资金已退款。"),
    FAILED(InboxMessage.Kind.REVERSAL_RESULT, "ERP 已明确拒绝原独立冲销，请核对原记录；原凭证和其他财务业务仍需分别处理。"),
    RETIRED(InboxMessage.Kind.REVERSAL_RESULT, "本次冲销已按安全依据结束，请核对原记录和业务恢复状态；这不表示已冲销或已退款。");

    private static final String PREFIX = "reversal:";
    private final InboxMessage.Kind kind;
    private final String content;
    ReversalNotice(InboxMessage.Kind kind, String content) { this.kind = kind; this.content = content; }
    public InboxMessage.Kind kind() { return kind; }
    public String content() { return content; }
    public String title() { return kind == InboxMessage.Kind.REVERSAL_RESULT ? "独立冲销结果更新" : "独立冲销需核对"; }

    /** 就绪和授权不等于已发送或已冲销。 */
    public static Optional<ReversalNotice> from(VoucherReversalPreparation value) {
        return Optional.ofNullable(switch (value.status()) {
            case UNAVAILABLE -> PREPARATION_UNAVAILABLE;
            case VOIDED -> PREPARATION_VOIDED;
            default -> null;
        });
    }

    /** 正常受理中及主动复查不制造异常，安全结束只采用独立持久事件。 */
    public static Optional<ReversalNotice> from(VoucherReversalOperation value) {
        return Optional.ofNullable(switch (value.status()) {
            case POSTED -> POSTED;
            case FAILED -> FAILED;
            case NOT_FOUND -> NOT_FOUND;
            case RECONCILING -> RECONCILING;
            case EXPIRED -> EXPIRED;
            case VOIDED -> value.failure() == VoucherReversalOperation.Failure.FINANCE_RETIRED ? null : SOURCE_CHANGED;
            case UNKNOWN -> value.failure() == null || value.failure() == VoucherReversalOperation.Failure.RECHECK_REQUESTED ? null : UNKNOWN;
            default -> null;
        });
    }

    /** 每次准备与由其授权的命令同号，新的尝试始终是新的身份。 */
    public String eventKey(UUID id) { return PREFIX + id + ":" + name(); }

    /** 来源只能解析为规范冲销编号和已声明事实，不允许其他业务键或 UUID 别名。 */
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
     * 事实来源不授予原凭证读取或冲销办理权限。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID reversalId, ReversalNotice notice) { }
}
