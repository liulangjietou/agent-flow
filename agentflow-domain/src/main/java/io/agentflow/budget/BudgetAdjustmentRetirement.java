package io.agentflow.budget;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 安全结束绑定已停止的原操作修订，不能用授权到期或查无取代无副作用证明。
 * @author owlzhangfq@gmail.com
 */
public record BudgetAdjustmentRetirement(UUID operationId, long operationVersion, Basis basis, String retiredBy, Instant retiredAt) {
    /** 具名决定与原修订都必须保存，不能只清除活动标记。 */
    public BudgetAdjustmentRetirement {
        if (operationId == null || operationVersion < 1 || basis == null || StringUtils.isBlank(retiredBy) || retiredBy.length() > 128
                || !retiredBy.equals(retiredBy.trim()) || retiredBy.chars().anyMatch(Character::isISOControl) || retiredAt == null) throw unsafe();
    }
    /** 先停止未发送队列，再与结束证明一并保存；已发送或争议事实保持原占用。 */
    public static BudgetAdjustmentRetirement from(BudgetAdjustmentOperation value, String actor, Instant now) {
        if (value == null || !value.safelyUnexecuted() || value.status() == BudgetAdjustmentOperation.Status.QUEUED) throw unsafe();
        var result = new BudgetAdjustmentRetirement(value.command().id(), value.version(), basis(value), actor, now);
        if (!result.matches(value)) throw unsafe(); return result;
    }
    /** 读取旧决定仍引用当时同一原指令及安全修订，不使用最新查询代替。 */
    public boolean matches(BudgetAdjustmentOperation value) {
        return value != null && operationId.equals(value.command().id()) && operationVersion == value.version()
                && value.safelyUnexecuted() && value.status() != BudgetAdjustmentOperation.Status.QUEUED && basis == basis(value)
                && !retiredBy.equals(value.command().source().employeeId()) && !retiredAt.isBefore(value.updatedAt());
    }
    private static Basis basis(BudgetAdjustmentOperation value) { return value.status() == BudgetAdjustmentOperation.Status.REJECTED ? Basis.REJECTED : Basis.NEVER_SENT; }
    private static DomainException unsafe() { return new DomainException("BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE", "Stopped original budget operation must prove no external adjustment before replacement"); }
    /** 默认日志只显示原指令标识。 */
    @Override public String toString() { return "BudgetAdjustmentRetirement[operationId=" + operationId + ", operationVersion=" + operationVersion + "]"; }
    /**
     * 已停止且从未发送，或者原系统明确保证整条命令无副作用拒绝。
     * @author owlzhangfq@gmail.com
     */
    public enum Basis { NEVER_SENT, REJECTED }
}
