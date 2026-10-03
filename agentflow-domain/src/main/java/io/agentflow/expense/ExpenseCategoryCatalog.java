package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.util.HashSet;
import java.util.List;

/**
 * 租户费用类别的一次完整修订；停用保留身份和单位，旧单据仍能追溯原版本。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseCategoryCatalog(String tenantId, long version, List<Category> categories) {
    public static final int MAX_CATEGORIES = 2000;

    /** 零版明确表示尚未配置，不生成默认企业类别。 */
    public ExpenseCategoryCatalog {
        text(tenantId, 64);
        if (version < 0 || categories == null || categories.size() > MAX_CATEGORIES
                || categories.stream().anyMatch(java.util.Objects::isNull) || version == 0 && !categories.isEmpty()) throw invalid();
        categories = List.copyOf(categories);
        if (categories.stream().map(Category::code).distinct().count() != categories.size()) throw invalid();
    }

    /** 类别身份不可删除；更名、停用和重新启用都产生新修订。 */
    public ExpenseCategoryCatalog revise(long expectedVersion, List<Category> replacement) {
        requireVersion(expectedVersion);
        var next = new ExpenseCategoryCatalog(tenantId, version + 1, replacement);
        var keys = new HashSet<>(next.categories.stream().map(Category::code).toList());
        if (categories.stream().anyMatch(category -> !keys.contains(category.code()))) {
            throw new DomainException("EXPENSE_CATEGORY_REMOVAL_FORBIDDEN", "Expense categories can be disabled but their identities cannot be removed");
        }
        if (version > 0 && categories.equals(next.categories)) throw new DomainException("EXPENSE_CONFIGURATION_UNCHANGED", "Expense category configuration has not changed");
        return next;
    }

    /** 发布及写入只接受操作者已经核对过的类别版本。 */
    public void requireVersion(long expected) {
        if (version != expected) throw new DomainException("CONCURRENCY_CONFLICT", "Expense category version changed");
    }

    /** 当前可用字典不包含停用类别，历史快照仍完整保留。 */
    public List<Category> activeCategories() { return categories.stream().filter(Category::active).toList(); }

    private static void text(String value, int maximum) {
        if (StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_CATEGORIES", "Expense category identities, names, units and revision must be valid"); }

    /**
     * 类别代码为稳定业务身份，名称用于展示，允许单位按配置顺序保留。
     * @author owlzhangfq@gmail.com
     */
    public record Category(String code, String name, List<ExpenseLine.Unit> units, boolean active) {
        /** 单位必须明确且唯一，不把空集合解释为任意单位。 */
        public Category {
            text(code, 64); text(name, 128);
            if (units == null || units.isEmpty() || units.size() > ExpenseLine.Unit.values().length
                    || units.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(units).size() != units.size()) throw invalid();
            units = List.copyOf(units);
        }
    }
}
