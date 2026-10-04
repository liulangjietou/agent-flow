package io.agentflow.expense;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Document;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Line;
import io.agentflow.expense.ExpenseSplitRiskEvidence.Scope;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 围绕跨单经济口径和路由结果验证，不将多个类别或同单多行重复计入审批金额。
 * @author owlzhangfq@gmail.com
 */
class ExpenseSplitRiskEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant FROM = Instant.parse("2026-09-27T10:00:00Z");
    private static final Scope SCOPE = new Scope("tenant", "employee", id(90), "CNY");
    private static final ExpenseSplitRiskPolicy.Rule RULE = new ExpenseSplitRiskPolicy.Rule(7, money("5000"));

    @Test void inclusiveRollingWindowUsesCurrentApprovedAmountAndFreezesSourceVersions() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"));
        var approved = document(2, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "5000"));
        var result = assess(primary, List.of(approved));
        assertThat(result.windowFrom()).isEqualTo(FROM); assertThat(result.assessedAt()).isEqualTo(NOW);
        assertThat(result.suspected()).isTrue(); assertThat(result.ownAmount()).isEqualTo(money("1000"));
        assertThat(result.routingAmount()).isEqualTo(money("6000"));
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("6000"), 2, true));
        assertThat(result.sources()).containsExactly(primary, approved);
        assertThat(result.sources().get(1).financialVersion()).isEqualTo(8);
        assertThat(result.sources().get(1).applicationVersion()).isEqualTo(12);
        assertThat(result.sources().get(1).roundNo()).isEqualTo(2);
    }

    @Test void thresholdEqualityDoesNotEscalateAndWindowExcludesOneMicrosecondOutsideEitherEnd() {
        var primary = document(1, NOW, ApplicationStatus.WITHDRAWN, line(1, "TAXI", "1000"));
        var result = assess(primary, List.of(document(2, FROM, ApplicationStatus.IN_APPROVAL, line(1, "TAXI", "4000")),
                document(3, FROM.minusNanos(1000), ApplicationStatus.APPROVED, line(1, "TAXI", "8000")),
                document(4, NOW.plusNanos(1000), ApplicationStatus.APPROVED, line(1, "TAXI", "8000"))));
        assertThat(result.suspected()).isFalse(); assertThat(result.routingAmount()).isEqualTo(money("1000"));
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("5000"), 2, false));
        assertThat(result.sources()).extracting(Document::reportId).containsExactly(id(1), id(2));
    }

    @Test void currentApprovalAndApprovedRoundsAreTheOnlyEffectiveCandidatesAndOwnOldRoundsNeverCount() {
        var primary = document(1, NOW, ApplicationStatus.RETURNED, line(1, "TAXI", "1000"));
        var candidates = new ArrayList<Document>();
        int ordinal = 2;
        for (var status : ApplicationStatus.values()) candidates.add(document(ordinal++, FROM, status, line(1, "TAXI", "1000")));
        candidates.add(document(1, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "9000")));
        var result = assess(primary, candidates);
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("3000"), 3, false));
        assertThat(result.sources()).hasSize(3);
        assertThat(result.sources().subList(1, 3)).extracting(Document::status)
                .containsExactlyInAnyOrder(ApplicationStatus.IN_APPROVAL, ApplicationStatus.APPROVED);
    }

    @Test void tenantEmployeeLegalEntityCurrencyAndCategoryMustMatch() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"));
        var candidates = List.of(
                scoped(2, new Scope("other", "employee", id(90), "CNY"), "TAXI", "9000"),
                scoped(3, new Scope("tenant", "other", id(90), "CNY"), "TAXI", "9000"),
                scoped(4, new Scope("tenant", "employee", id(91), "CNY"), "TAXI", "9000"),
                scoped(5, new Scope("tenant", "employee", id(90), "USD"), "TAXI", "9000"),
                scoped(6, SCOPE, "HOTEL", "9000"));
        var result = assess(primary, candidates);
        assertThat(result.suspected()).isFalse(); assertThat(result.sources()).containsExactly(primary);
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("1000"), 1, false));
    }

    @Test void severalLinesCountOncePerReportAndZeroAmountDoesNotCreateAnotherReport() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(2, "TAXI", "3000"), line(1, "TAXI", "3000"));
        var zero = document(2, FROM, ApplicationStatus.IN_APPROVAL, line(1, "TAXI", "0"));
        var alone = assess(primary, List.of(zero));
        assertThat(alone.suspected()).isFalse(); assertThat(alone.sources()).containsExactly(primary);
        assertThat(alone.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("6000"), 1, false));
        var peer = document(3, FROM, ApplicationStatus.IN_APPROVAL, line(1, "TAXI", "1"), line(2, "TAXI", "2"));
        var together = assess(primary, List.of(zero, peer));
        assertThat(together.routingAmount()).isEqualTo(money("6003"));
        assertThat(together.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("6003"), 2, true));
        assertThat(together.sources().get(0).lines()).extracting(Line::lineNo).containsExactly(1, 2);
    }

    @Test void severalTriggeredCategoriesUseTheirMaximumRatherThanSum() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(2, "HOTEL", "1000"), line(1, "TAXI", "1000"));
        var peer = document(2, FROM, ApplicationStatus.IN_APPROVAL, line(1, "TAXI", "5000"), line(2, "HOTEL", "6000"));
        var result = assess(primary, List.of(peer));
        assertThat(result.routingAmount()).isEqualTo(money("7000"));
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("HOTEL", money("7000"), 2, true),
                new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("6000"), 2, true));
    }

    @Test void routingNeverFallsBelowWholePrimaryAmountAndZeroPrimaryCategoryCannotTrigger() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"), line(2, "MEAL", "11000"), line(3, "HOTEL", "0"));
        var peer = document(2, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "5000"), line(2, "HOTEL", "99000"));
        var result = assess(primary, List.of(peer));
        assertThat(result.suspected()).isTrue(); assertThat(result.routingAmount()).isEqualTo(money("12000"));
        assertThat(result.categories()).containsExactly(new ExpenseSplitRiskEvidence.CategoryTotal("MEAL", money("11000"), 1, false),
                new ExpenseSplitRiskEvidence.CategoryTotal("TAXI", money("6000"), 2, true));
    }

    @Test void sourceOrderAndListsRemainStableAfterCallerChangesInput() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"));
        var candidates = new ArrayList<>(List.of(document(4, NOW, ApplicationStatus.APPROVED, line(1, "TAXI", "1000")),
                document(3, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "1000")),
                document(2, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "1000"))));
        var result = assess(primary, candidates); candidates.clear();
        assertThat(result.sources()).extracting(Document::reportId).containsExactly(id(1), id(2), id(3), id(4));
        assertThatThrownBy(() -> result.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.categories().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.sources().get(0).lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void thousandDocumentBoundaryIsExplicitAndExcessIsNotSilentlyTruncated() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1"));
        var candidates = IntStream.rangeClosed(2, 1000)
                .mapToObj(i -> document(i, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "1"))).toList();
        assertThat(assess(primary, candidates).sources()).hasSize(1000);
        var excess = new ArrayList<>(candidates); excess.add(document(1001, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "1")));
        assertCode(() -> assess(primary, excess), "EXPENSE_SPLIT_SOURCE_LIMIT");
    }

    @Test void duplicateCurrentReportOrApplicationBindingCannotDoubleTheAggregate() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"));
        var peer = document(2, FROM, ApplicationStatus.IN_APPROVAL, line(1, "TAXI", "3000"));
        assertCode(() -> assess(primary, List.of(peer, peer)), "EXPENSE_SPLIT_EVIDENCE_INVALID");
        var sameApplication = new Document(id(3), peer.applicationId(), 12, 8, 2, SCOPE, FROM, ApplicationStatus.APPROVED, peer.lines());
        assertCode(() -> assess(primary, List.of(peer, sameApplication)), "EXPENSE_SPLIT_EVIDENCE_INVALID");
    }

    @Test void primaryCurrencyAndAssessmentTimeCannotSilentlyChangeTheConfiguredRule() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"));
        assertCode(() -> ExpenseSplitRiskEvidence.assess(new ExpenseSplitRiskPolicy.Rule(7, new Money(new BigDecimal("5000"), "USD")), primary, List.of(), NOW),
                "EXPENSE_SPLIT_CURRENCY_MISMATCH");
        assertCode(() -> ExpenseSplitRiskEvidence.assess(RULE, primary, List.of(), NOW.plus(Duration.ofHours(1))), "EXPENSE_SPLIT_EVIDENCE_INVALID");
    }

    @Test void duplicateLineNumbersAndMixedCurrenciesCannotBecomeFrozenEvidence() {
        assertCode(() -> document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "1000"), line(1, "HOTEL", "1000")), "EXPENSE_SPLIT_EVIDENCE_INVALID");
        assertCode(() -> document(1, NOW, ApplicationStatus.DRAFT, new Line(1, "TAXI", new Money(new BigDecimal("1000"), "USD"))),
                "EXPENSE_SPLIT_EVIDENCE_INVALID");
    }

    @Test void aggregateOutsideSupportedRoutingMoneyFailsInsteadOfWrappingOrClamping() {
        var primary = document(1, NOW, ApplicationStatus.DRAFT, line(1, "TAXI", "999999999999999.99"));
        assertCode(() -> assess(primary, List.of(document(2, FROM, ApplicationStatus.APPROVED, line(1, "TAXI", "0.01")))), "INVALID_MONEY");
    }

    private static ExpenseSplitRiskEvidence.Assessment assess(Document primary, List<Document> candidates) {
        return ExpenseSplitRiskEvidence.assess(RULE, primary, candidates, NOW);
    }
    private static Document document(int id, Instant at, ApplicationStatus status, Line... lines) {
        return new Document(id(id), id(id + 10000), 12, 8, 2, SCOPE, at, status, List.of(lines));
    }
    private static Document scoped(int id, Scope scope, String category, String amount) {
        return new Document(id(id), id(id + 10000), 12, 8, 2, scope, FROM, ApplicationStatus.APPROVED,
                List.of(new Line(1, category, new Money(new BigDecimal(amount), scope.currency()))));
    }
    private static Line line(int number, String category, String amount) { return new Line(number, category, money(amount)); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), "CNY"); }
    private static UUID id(int number) { return new UUID(0, number); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code);
    }
}
