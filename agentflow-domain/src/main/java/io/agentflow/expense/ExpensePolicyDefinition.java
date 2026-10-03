package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 可发布的费用制度定义；规则按显式顺序首次匹配，不包含任意脚本或企业默认标准。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePolicyDefinition(String name, List<Rule> rules) {
    public static final int MAX_RULES = 200;
    public static final int MAX_INVOICE_AGE_DAYS = 36600;
    private static final int MAX_SELECTOR_VALUES = 200;

    /** 草稿允许空规则，发布前必须补全；重复身份或相同选择条件不能遮蔽下一条规则。 */
    public ExpensePolicyDefinition {
        text(name, 128);
        if (rules == null || rules.size() > MAX_RULES || rules.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        rules = List.copyOf(rules);
        if (rules.stream().map(Rule::key).distinct().count() != rules.size() || rules.stream().map(Rule::match).distinct().count() != rules.size()) throw invalid();
    }

    /** 发布只引用已配置、启用的类别；类别归属在跨聚合边界统一检查。 */
    public void requirePublishable(ExpenseCategoryCatalog categories) {
        if (rules.isEmpty() || categories.version() == 0) throw new DomainException("EXPENSE_POLICY_INCOMPLETE", "Expense policy rules and a category revision are required before publishing");
        var active = new HashSet<>(categories.activeCategories().stream().map(ExpenseCategoryCatalog.Category::code).toList());
        if (active.isEmpty() || rules.stream().flatMap(rule -> rule.match().categoryCodes().stream()).anyMatch(code -> !active.contains(code))) {
            throw new DomainException("EXPENSE_POLICY_CATEGORY_UNAVAILABLE", "Published expense policy must refer to active categories in the confirmed revision");
        }
    }

    private static List<String> selectors(List<String> values) {
        if (values == null || values.size() > MAX_SELECTOR_VALUES) throw invalid();
        values.forEach(value -> text(value, 64));
        if (new HashSet<>(values).size() != values.size()) throw invalid();
        return values.stream().sorted().toList();
    }
    private static void text(String value, int maximum) {
        if (StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_POLICY_DEFINITION", "Expense policy rules, selectors and constraints are invalid or inconsistent"); }

    /**
     * 列表顺序就是优先级；稳定规则键用于判定证据，不依赖展示名称。
     * @author owlzhangfq@gmail.com
     */
    public record Rule(String key, String name, Match match, Constraints constraints) {
        /** 金额上限必须明确同一原币条件，禁止跨币种隐式套用企业标准。 */
        public Rule {
            text(key, 64); text(name, 128);
            if (match == null || constraints == null || constraints.unitPriceLimit() != null
                    && !constraints.unitPriceLimit().currency().equals(match.currency())) throw invalid();
        }
    }

    /**
     * 空选择集合表示任意值；职级和城市等级必须由企业事实源确定，申请人不能自行声明。
     * @author owlzhangfq@gmail.com
     */
    public record Match(List<UUID> legalEntityIds, List<String> categoryCodes, List<String> cityTiers,
                        List<String> employeeGrades, LocalDate fromDate, LocalDate throughDate, String currency) {
        /** 日期区间包含两端，条件集合规范化后比较，拒绝倒置日期与重复成员。 */
        public Match {
            if (legalEntityIds == null || legalEntityIds.size() > MAX_SELECTOR_VALUES || legalEntityIds.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(legalEntityIds).size() != legalEntityIds.size()
                    || fromDate != null && throughDate != null && fromDate.isAfter(throughDate)) throw invalid();
            legalEntityIds = legalEntityIds.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
            categoryCodes = selectors(categoryCodes); cityTiers = selectors(cityTiers); employeeGrades = selectors(employeeGrades);
            if (currency != null) Money.zero(currency);
        }

        /** 本地已知维度不能被远端回执改换；职级和城市等级继续由可信事实源匹配。 */
        public boolean acceptsKnownFacts(UUID legalEntityId, ExpenseLine line) {
            return (legalEntityIds.isEmpty() || legalEntityIds.contains(legalEntityId))
                    && (categoryCodes.isEmpty() || categoryCodes.contains(line.categoryCode()))
                    && (currency == null || currency.equals(line.claimedGross().currency()))
                    && (fromDate == null || !line.incurredOn().isBefore(fromDate))
                    && (throughDate == null || !line.incurredOn().isAfter(throughDate));
        }
    }

    /**
     * 同一条匹配规则同时约束单价、时限、等级与事前申请，禁止项不能再附带放行条件。
     * @author owlzhangfq@gmail.com
     */
    public record Constraints(Effect effect, Money unitPriceLimit, ExpenseLine.Unit limitUnit, Integer invoiceMaxAgeDays,
                              AgeAction invoiceAgeAction, List<String> allowedServiceLevels, boolean priorRequestRequired) {
        /** 限制成组出现且无默认金额或天数，允许零上限但不接受负金额。 */
        public Constraints {
            if (effect == null || (unitPriceLimit == null) != (limitUnit == null) || (invoiceMaxAgeDays == null) != (invoiceAgeAction == null)
                    || invoiceMaxAgeDays != null && (invoiceMaxAgeDays < 0 || invoiceMaxAgeDays > MAX_INVOICE_AGE_DAYS)) throw invalid();
            allowedServiceLevels = selectors(allowedServiceLevels);
            if (effect == Effect.DENY && (unitPriceLimit != null || invoiceMaxAgeDays != null || !allowedServiceLevels.isEmpty() || priorRequestRequired)) throw invalid();
        }
    }

    /**
     * 未命中规则不自动视为允许；开放范围必须显式发布 ALLOW 规则。
     * @author owlzhangfq@gmail.com
     */
    public enum Effect { ALLOW, DENY }
    /**
     * 票据时限可以阻断或要求说明，不能由远端临时改变已经发布的处理方式。
     * @author owlzhangfq@gmail.com
     */
    public enum AgeAction { REJECT, REQUIRE_REASON }
}
