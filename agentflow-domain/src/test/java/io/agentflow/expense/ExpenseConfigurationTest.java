package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 配置边界关注历史身份、并发版本与发布不变量，不写入任何企业默认标准。
 * @author owlzhangfq@gmail.com
 */
class ExpenseConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test void categoryIdentitySurvivesDisableRenameAndReenable() {
        var empty = new ExpenseCategoryCatalog("demo", 0, List.of());
        var first = empty.revise(0, List.of(category("hotel", "住宿", true)));
        var disabled = first.revise(1, List.of(category("hotel", "酒店住宿", false)));
        assertThat(disabled.activeCategories()).isEmpty();
        assertThat(first.activeCategories()).containsExactly(category("hotel", "住宿", true));
        assertThat(disabled.revise(2, first.categories()).version()).isEqualTo(3);
        assertCode(() -> first.revise(1, List.of()), "EXPENSE_CATEGORY_REMOVAL_FORBIDDEN");
        assertCode(() -> first.revise(0, List.of()), "CONCURRENCY_CONFLICT");
        assertCode(() -> first.revise(1, first.categories()), "EXPENSE_CONFIGURATION_UNCHANGED");
    }

    @Test void catalogCopiesCallerCollectionsAndRejectsDuplicateIdentityAndUnits() {
        var units = new ArrayList<>(List.of(ExpenseLine.Unit.NIGHT));
        var item = new ExpenseCategoryCatalog.Category("hotel", "住宿", units, true);
        var values = new ArrayList<>(List.of(item));
        var catalog = new ExpenseCategoryCatalog("demo", 1, values);
        values.clear(); units.clear();
        assertThat(catalog.categories()).containsExactly(item);
        assertThat(item.units()).containsExactly(ExpenseLine.Unit.NIGHT);
        assertCode(() -> new ExpenseCategoryCatalog("demo", 0, List.of(item)), "INVALID_EXPENSE_CATEGORIES");
        assertCode(() -> new ExpenseCategoryCatalog("demo", 1, List.of(item, item)), "INVALID_EXPENSE_CATEGORIES");
        assertCode(() -> new ExpenseCategoryCatalog.Category("hotel", "住宿", List.of(ExpenseLine.Unit.NIGHT, ExpenseLine.Unit.NIGHT), true), "INVALID_EXPENSE_CATEGORIES");
    }

    @Test void publicationIsImmutableAcrossFurtherDraftAndCategoryChanges() {
        var first = ExpensePolicyDraft.create("demo", "travel", definition("首版", "hotel"));
        var publication = first.publish(1, catalog(), "finance-admin", NOW, "首次启用");
        var secondDraft = publication.draft().revise(1, definition("第二版", "hotel"));
        var second = secondDraft.publish(2, catalog(), "finance-admin", NOW.plusSeconds(1), "调整制度");
        assertThat(publication.version().definition().name()).isEqualTo("首版");
        assertThat(second.version().version()).isEqualTo(2);
        assertThat(second.version().draftRevision()).isEqualTo(2);
        assertThat(second.version().policyId()).isEqualTo(first.id());
        assertThat(publication.version().categoryRevision()).isEqualTo(1);
        assertCode(() -> publication.draft().publish(1, catalog(), "finance-admin", NOW, "重复"), "EXPENSE_CONFIGURATION_UNCHANGED");
        assertCode(() -> secondDraft.publish(1, catalog(), "finance-admin", NOW, "过期"), "CONCURRENCY_CONFLICT");
    }

    @Test void emptyDraftCanBeSavedButCannotBePublished() {
        var draft = ExpensePolicyDraft.create("demo", "travel", new ExpensePolicyDefinition("待配置", List.of()));
        assertCode(() -> draft.publish(1, catalog(), "admin", NOW, "不完整"), "EXPENSE_POLICY_INCOMPLETE");
        assertCode(() -> ExpensePolicyDraft.create("demo", "travel", definition("制度", "hotel"))
                .publish(1, new ExpenseCategoryCatalog("demo", 0, List.of()), "admin", NOW, "未配置目录"), "EXPENSE_POLICY_INCOMPLETE");
    }

    @Test void publicationRejectsUnknownDisabledAndForeignTenantCategories() {
        var draft = ExpensePolicyDraft.create("demo", "travel", definition("制度", "hotel"));
        assertCode(() -> draft.publish(1, new ExpenseCategoryCatalog("other", 1, catalog().categories()), "admin", NOW, "跨租户"), "EXPENSE_POLICY_CATEGORY_UNAVAILABLE");
        assertCode(() -> draft.publish(1, new ExpenseCategoryCatalog("demo", 1, List.of(category("hotel", "住宿", false))), "admin", NOW, "停用类别"), "EXPENSE_POLICY_CATEGORY_UNAVAILABLE");
        assertCode(() -> definition("制度", "unknown").requirePublishable(catalog()), "EXPENSE_POLICY_CATEGORY_UNAVAILABLE");
    }

    @Test void selectorsNormalizeOrderAndRejectShadowedIdenticalMatches() {
        var one = match(List.of("hotel", "meal"), List.of("tier-2", "tier-1"), "CNY");
        var two = match(List.of("meal", "hotel"), List.of("tier-1", "tier-2"), "CNY");
        assertThat(one).isEqualTo(two);
        assertCode(() -> new ExpensePolicyDefinition("重复匹配", List.of(rule("one", one), rule("two", two))), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> match(List.of("hotel", "hotel"), List.of(), "CNY"), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Match(List.of(), List.of(), List.of(), List.of(), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null), "INVALID_EXPENSE_POLICY_DEFINITION");
    }

    @Test void monetaryLimitRequiresMatchingCurrencyAndExplicitUnit() {
        var amount = new Money(new BigDecimal("12.00"), "CNY");
        var cap = new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, amount, ExpenseLine.Unit.NIGHT, null, null, List.of(), false);
        assertCode(() -> new ExpensePolicyDefinition.Rule("hotel", "住宿", match(List.of("hotel"), List.of(), "USD"), cap), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, amount, null, null, null, List.of(), false), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertThat(new ExpensePolicyDefinition.Rule("hotel", "住宿", match(List.of("hotel"), List.of(), "CNY"), cap).constraints().unitPriceLimit()).isEqualTo(amount);
    }

    @Test void invoiceAgeAndForbiddenEffectsCannotContainAmbiguousRules() {
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, 20, null, List.of(), false), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, -1, ExpensePolicyDefinition.AgeAction.REJECT, List.of(), false), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.DENY, null, null, null, null, List.of("economy"), false), "INVALID_EXPENSE_POLICY_DEFINITION");
        assertCode(() -> new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.DENY, null, null, null, null, List.of(), true), "INVALID_EXPENSE_POLICY_DEFINITION");
    }

    @Test void restoredDraftCannotClaimMorePublicationsThanRevisions() {
        assertCode(() -> new ExpensePolicyDraft(UUID.randomUUID(), "demo", "travel", 2, definition("制度", "hotel"), 3, 2), "INVALID_EXPENSE_POLICY_DRAFT");
        assertCode(() -> new ExpensePolicyDraft(UUID.randomUUID(), "demo", "travel", 2, definition("制度", "hotel"), 1, 0), "INVALID_EXPENSE_POLICY_DRAFT");
    }

    private static ExpenseCategoryCatalog.Category category(String code, String name, boolean active) { return new ExpenseCategoryCatalog.Category(code, name, List.of(ExpenseLine.Unit.NIGHT), active); }
    private static ExpenseCategoryCatalog catalog() { return new ExpenseCategoryCatalog("demo", 1, List.of(category("hotel", "住宿", true))); }
    private static ExpensePolicyDefinition definition(String name, String category) { return new ExpensePolicyDefinition(name, List.of(rule("rule-1", match(List.of(category), List.of(), "CNY")))); }
    private static ExpensePolicyDefinition.Rule rule(String key, ExpensePolicyDefinition.Match match) { return new ExpensePolicyDefinition.Rule(key, key, match, new ExpensePolicyDefinition.Constraints(ExpensePolicyDefinition.Effect.ALLOW, null, null, null, null, List.of(), false)); }
    private static ExpensePolicyDefinition.Match match(List<String> categories, List<String> cities, String currency) { return new ExpensePolicyDefinition.Match(List.of(), categories, cities, List.of(), null, null, currency); }
    private static void assertCode(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code); }
}
