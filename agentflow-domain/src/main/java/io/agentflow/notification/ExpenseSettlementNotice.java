package io.agentflow.notification;

import io.agentflow.expense.ExpenseSettlement;
import java.util.Optional;
import java.util.UUID;

/**
 * 业务核销与银行、预算操作分别通知；每条事实固定结算修订。
 * @author owlzhangfq@gmail.com
 */
public enum ExpenseSettlementNotice {
    BLOCKED("原报销核销遇到阻塞，原资金事实保持，请在原申请核对未完成部分。"),
    BUDGET_REJECTED("原报销的预算实际占用未通过，已核销资源保持，请在原申请核对预算处理。"),
    REVIEW_REQUIRED("原报销的资金或凭证依据需要人工核对，原结算已进入复核状态。"),
    SETTLED("原报销的本地资源与预算实际占用已完成核销；付款凭证、归档及后续调整仍是独立步骤。");

    private static final String PREFIX = "expense-settlement:";
    private final String content;
    ExpenseSettlementNotice(String content) { this.content = content; }
    public InboxMessage.Kind kind() { return this == SETTLED ? InboxMessage.Kind.EXPENSE_SETTLEMENT_RESULT : InboxMessage.Kind.EXPENSE_SETTLEMENT_ATTENTION; }
    public String title() { return this == SETTLED ? "报销核销已完成" : "报销结算需核对"; }
    public String content() { return content; }
    /** 排队和等待预算不表示结算完成，明确的持久阻塞才生成待处理提示。 */
    public static Optional<ExpenseSettlementNotice> from(ExpenseSettlement value) {
        return Optional.ofNullable(switch (value.status()) {
            case BLOCKED -> BLOCKED;
            case BUDGET_REJECTED -> BUDGET_REJECTED;
            case REVIEW_REQUIRED -> REVIEW_REQUIRED;
            case SETTLED -> SETTLED;
            default -> null;
        });
    }
    /** 人工恢复后发生的新阻塞有新修订，不能被此前同类事实吞掉。 */
    public String eventKey(UUID reportId, long version) { return PREFIX + reportId + ":" + version + ":" + name(); }
    /** 拒绝非规范编号、修订及不能证明的事实。 */
    public static Optional<Source> source(String key) {
        if (key == null || !key.startsWith(PREFIX)) return Optional.empty();
        String[] parts = key.substring(PREFIX.length()).split(":", -1); if (parts.length != 3) return Optional.empty();
        try {
            UUID id = UUID.fromString(parts[0]); long version = Long.parseLong(parts[1]);
            return id.toString().equals(parts[0]) && version > 0 && Long.toString(version).equals(parts[1])
                    ? Optional.of(new Source(id, version, valueOf(parts[2]))) : Optional.empty();
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    /**
     * 只定位原修订，不携带财务写入授权。
     * @author owlzhangfq@gmail.com
     */
    public record Source(UUID reportId, long version, ExpenseSettlementNotice notice) { }
}
