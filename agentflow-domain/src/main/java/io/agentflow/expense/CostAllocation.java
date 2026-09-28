package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 成本中心与项目分摊；本位币整分按原权重分配并保持精确平衡。
 * @author owlzhangfq@gmail.com
 */
public record CostAllocation(String costCenter, String projectCode, Money amount) {
    /** 成本对象由外部主数据校验，此处只保证稳定标识和金额结构。 */
    public CostAllocation {
        if (StringUtils.isBlank(costCenter) || costCenter.length() > 128 || amount == null
                || projectCode != null && (projectCode.isBlank() || projectCode.length() > 128)) {
            throw new DomainException("INVALID_ALLOCATION", "A cost center and amount are required");
        }
    }

    /** 最大余数法分配整分；相同余数按原顺序确定，单次转换不积累分摊误差。 */
    public static List<CostAllocation> apportion(List<CostAllocation> weights, Money total) {
        if (CollectionUtils.isEmpty(weights)) throw new DomainException("INVALID_ALLOCATION", "Allocation weights are required");
        var sum = Money.zero(weights.get(0).amount.currency());
        for (var weight : weights) sum = sum.plus(weight.amount);
        if (sum.value().signum() == 0) throw new DomainException("INVALID_ALLOCATION", "Allocation weight total must be positive");
        BigInteger cents = total.value().movePointRight(Money.SCALE).toBigIntegerExact();
        BigInteger denominator = sum.value().unscaledValue();
        var values = new ArrayList<BigInteger>(); var remainders = new ArrayList<BigInteger>();
        BigInteger assigned = BigInteger.ZERO;
        for (var weight : weights) {
            BigInteger[] division = cents.multiply(weight.amount.value().unscaledValue()).divideAndRemainder(denominator);
            values.add(division[0]); remainders.add(division[1]); assigned = assigned.add(division[0]);
        }
        var order = new ArrayList<Integer>();
        for (int index = 0; index < weights.size(); index++) order.add(index);
        order.sort(Comparator.<Integer, BigInteger>comparing(remainders::get).reversed());
        int remainder = cents.subtract(assigned).intValueExact();
        for (int rank = 0; rank < remainder; rank++) {
            int index = order.get(rank); values.set(index, values.get(index).add(BigInteger.ONE));
        }
        var result = new ArrayList<CostAllocation>();
        for (int index = 0; index < weights.size(); index++) {
            var original = weights.get(index);
            result.add(new CostAllocation(original.costCenter, original.projectCode,
                    new Money(new java.math.BigDecimal(values.get(index), Money.SCALE), total.currency())));
        }
        return List.copyOf(result);
    }
}
