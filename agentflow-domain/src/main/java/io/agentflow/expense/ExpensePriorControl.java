package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.math.BigDecimal;
import org.apache.commons.lang3.StringUtils;

/**
 * 类别明确配置的事前控制；容差为追加审批阈值，历史未配置记录仍使用原硬上限。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePriorControl(Mode mode, BigDecimal toleranceFraction) {
    /** 比例只能随容差模式明确提供，不能用缺省比例推断企业规则。 */
    public ExpensePriorControl {
        if (mode == null || (mode == Mode.TOLERANCE
                ? toleranceFraction == null || toleranceFraction.signum() < 0 || toleranceFraction.compareTo(BigDecimal.ONE) > 0
                    || toleranceFraction.stripTrailingZeros().scale() > 6
                : toleranceFraction != null)) throw invalid();
    }

    /** 严格及不控制模式保留原计划金额作为参考，不虚增授权数字。 */
    public BigDecimal referenceFraction() { return mode == Mode.TOLERANCE ? toleranceFraction : BigDecimal.ZERO; }

    /** 只有严格模式把参考金额作为不能超过的硬上限。 */
    public boolean hardLimit() { return mode == Mode.STRICT; }

    private static DomainException invalid() {
        return new DomainException("INVALID_EXPENSE_PRIOR_CONTROL", "Prior request control mode, fraction and source must be valid");
    }

    /**
     * 控制模式由管理员类别修订明确选择，不由已有金额或比例推断。
     * @author owlzhangfq@gmail.com
     */
    public enum Mode { STRICT, TOLERANCE, NONE }

    /**
     * 随批准行固定类别身份和当时完整控制，类别改版不会追改已批准额度。
     * @author owlzhangfq@gmail.com
     */
    public record Snapshot(String categoryCode, long categoryRevision, ExpensePriorControl control) {
        /** 新依据不能缺失类别、真实修订或模式。 */
        public Snapshot {
            if (StringUtils.isBlank(categoryCode) || categoryCode.length() > 64 || !categoryCode.equals(categoryCode.strip())
                    || categoryCode.chars().anyMatch(Character::isISOControl) || categoryRevision < 1 || control == null) throw invalid();
        }
    }
}
