package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 一次不可改写的预算命令；重提和核减原子替换原占用，重试继续使用同一编号和摘要。
 * @author owlzhangfq@gmail.com
 */
public record BudgetCommand(UUID id, String tenantId, Action action, BudgetPrecheckPort.Request position, Expected expected) {
    /** 首次冻结没有前置台账；释放后的重新冻结及其他后续命令沿用上次确认的版本和凭据。 */
    public BudgetCommand {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || action == null || position == null
                || action != Action.FREEZE && expected == null) throw invalid();
    }

    /**
     * 使用固定字段顺序和 UTF-8 字节长度编码，不依赖 JSON 属性顺序、空值省略或金额表示方式。
     * 外部系统按同一算法核对摘要后，必须将编号、摘要和结果一起持久化。
     */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, "agentflow-budget-command-1", id.toString(), tenantId, action.name(), position.reportId().toString(),
                    Integer.toString(position.roundNo()), Long.toString(position.financialVersion()), position.employeeId(),
                    position.legalEntityId().toString(), position.baseCurrency(), position.accountingDate().toString(),
                    Integer.toString(position.allocations().size()));
            for (var item : position.allocations()) {
                add(digest, Integer.toString(item.expenseLineNo()), Integer.toString(item.allocationNo()), item.categoryCode(),
                        item.cost().costCenter(), item.cost().projectCode(), item.cost().amount().value().toPlainString(), item.cost().amount().currency());
            }
            add(digest, expected == null ? null : Long.toString(expected.revision()), expected == null ? null : expected.reference());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void add(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }

    /**
     * ADJUST 以完整新分摊原子替换既有冻结，不能先释放成功再冻结失败。
     * RELEASE 和 CONSUME 的分摊必须等于最近确认的冻结，由本地台账在登记时核对。
     * @author owlzhangfq@gmail.com
     */
    public enum Action { FREEZE, ADJUST, RELEASE, CONSUME }

    /**
     * 预算系统按单据顺序递增的台账版本，防止迟到命令覆盖后续预算操作。
     * @author owlzhangfq@gmail.com
     */
    public record Expected(long revision, String reference) {
        /** 留出下一版本空间，不允许没有真实来源的前置状态。 */
        public Expected {
            if (revision < 1 || revision == Long.MAX_VALUE || StringUtils.isBlank(reference) || reference.length() > 128) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_COMMAND", "Budget command identity, position and expected ledger are required"); }
}
