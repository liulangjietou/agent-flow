package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.finance.FinanceCatalog;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 报销建议只读边界、精确来源、分摊比例、双版本确认与崩溃租约的领域验证。
 * @author owlzhangfq@gmail.com
 */
class ExpenseDraftAssistRunTest {
    private static final Instant AT = Instant.parse("2026-10-04T00:00:00Z");
    private static final ExpenseDraftAssistInput.Leg LEG = new ExpenseDraftAssistInput.Leg(1,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), "SH", "项目现场调研");

    @Test void confirmsOnlyExplicitPartsAndRetainsOriginalFinancialVersionsAndSuggestion() {
        var run = completed(); var input = run.context().input(); var suggestion = run.state().suggestion();
        var selected = new ArrayList<>(List.of(new ExpenseDraftAssistRun.Selection("line1",
                Set.of(ExpenseDraftAssistRun.Part.ITINERARY, ExpenseDraftAssistRun.Part.CATEGORY))));
        run.confirm(3, 3, 5, "alice", selected, "分摊仍需人工填写", AT.plusSeconds(3)); selected.clear();
        assertThat(run.state().status()).isEqualTo(ExpenseDraftAssistRun.Status.CONFIRMED);
        assertThat(run.state().review().selected().get(0).parts()).doesNotContain(ExpenseDraftAssistRun.Part.ALLOCATION);
        assertThat(run.context().input()).isSameAs(input);
        assertThat(input.applicationVersion()).isEqualTo(3); assertThat(input.financialVersion()).isEqualTo(5);
        assertThat(run.state().suggestion()).isSameAs(suggestion);
        assertThat(ExpenseDraftAssistRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
        assertThatThrownBy(() -> run.state().review().selected().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void noReliableRecommendationMayCompleteEmptyAndBeDismissedWithoutInventingALine() {
        var run = run(); run.start(1, AT, AT.plusSeconds(30));
        run.complete(2, new ExpenseDraftSuggestion("synthetic", "v1", ExpenseDraftAssistRun.PROMPT_VERSION, List.of()), AT.plusSeconds(1));
        assertThat(run.state().suggestion().lines()).isEmpty();
        assertCode(() -> run.confirm(3, 3, 5, "alice", List.of(selection()), null, AT.plusSeconds(2)), "INVALID_AGENT_REVIEW");
        run.dismiss(3, "alice", "补充行程信息后重新生成", AT.plusSeconds(2));
        assertThat(ExpenseDraftAssistRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
    }

    @ParameterizedTest @ValueSource(strings = {"application", "financial", "expired", "actor", "proposal"})
    void rejectsConfirmationAfterSourceChangeOrFromAnotherActor(String variant) {
        var run = completed();
        Runnable action = () -> run.confirm(3, variant.equals("application") ? 4 : 3, variant.equals("financial") ? 6 : 5,
                variant.equals("actor") ? "admin" : "alice", List.of(variant.equals("proposal")
                        ? new ExpenseDraftAssistRun.Selection("unknown", Set.of(ExpenseDraftAssistRun.Part.ITINERARY)) : selection()),
                null, AT.plusSeconds(variant.equals("expired") ? 300 : 3));
        assertCode(action, Set.of("actor", "proposal").contains(variant) ? "INVALID_AGENT_REVIEW" : "AGENT_INPUT_CHANGED");
        assertThat(run.state().version()).isEqualTo(3);
        run.dismiss(3, "alice", null, AT.plusSeconds(600));
        assertThat(ExpenseDraftAssistRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
    }

    @Test void confirmationMustNameItineraryAndCannotSelectTheSameProposalTwice() {
        assertCode(() -> new ExpenseDraftAssistRun.Selection("line1", Set.of(ExpenseDraftAssistRun.Part.ALLOCATION)), "INVALID_AGENT_REVIEW");
        var run = completed();
        assertCode(() -> run.confirm(3, 3, 5, "alice", List.of(selection(), selection()), null, AT.plusSeconds(3)), "INVALID_AGENT_REVIEW");
    }

    @Test void expiredExecutionCannotRestartOrReplaceItsStableFailure() {
        var run = run(); run.start(1, AT, AT.plusSeconds(30));
        assertCode(() -> run.start(2, AT.plusSeconds(1), AT.plusSeconds(60)), "AGENT_RUN_STATE_CONFLICT");
        assertCode(() -> run.complete(2, suggestion(input()), AT.plusSeconds(30)), "AGENT_RUN_STATE_CONFLICT");
        run.fail(2, AssistRun.Failure.MODEL_TIMEOUT, AT.plusSeconds(30));
        assertCode(() -> run.complete(3, suggestion(input()), AT.plusSeconds(31)), "AGENT_RUN_STATE_CONFLICT");
        assertThat(ExpenseDraftAssistRun.restore(run.context(), run.state()).state().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
    }

    @ParameterizedTest @ValueSource(strings = {"leg", "category", "unit", "center", "project", "digest", "missing-leg", "missing-catalog", "prompt"})
    void rejectsSuggestedTargetsOrEvidenceOutsideThisFrozenInput(String variant) {
        var run = run(); var input = run.context().input(); run.start(1, AT, AT.plusSeconds(60));
        var evidence = new ArrayList<>(List.of(input.reference(LEG.sourceId()), input.reference(ExpenseDraftAssistInput.CATALOG)));
        if (variant.equals("digest")) evidence.set(0, new AssistInput.Reference(LEG.sourceId(), "f".repeat(64)));
        if (variant.equals("missing-leg")) evidence.remove(0);
        if (variant.equals("missing-catalog")) evidence.remove(1);
        var line = new ExpenseDraftSuggestion.Line("line1", variant.equals("leg") ? 2 : 1, variant.equals("category") ? "UNKNOWN" : "HOTEL",
                variant.equals("unit") ? ExpenseLine.Unit.KILOMETER : ExpenseLine.Unit.NIGHT, "现场调研住宿",
                List.of(new ExpenseDraftSuggestion.Allocation(variant.equals("center") ? "FOREIGN" : "IT",
                        variant.equals("project") ? "HIDDEN" : "P01", new BigDecimal("100"))), evidence);
        var output = new ExpenseDraftSuggestion("synthetic", "v1", variant.equals("prompt") ? "other" : ExpenseDraftAssistRun.PROMPT_VERSION, List.of(line));
        assertCode(() -> run.complete(2, output, AT.plusSeconds(1)), "INVALID_AGENT_OUTPUT");
        assertThat(run.state().status()).isEqualTo(ExpenseDraftAssistRun.Status.RUNNING);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "100.01", "0.001"})
    void rejectsInvalidAllocationPercent(String percent) {
        assertCode(() -> new ExpenseDraftSuggestion.Allocation("IT", null, new BigDecimal(percent)), "INVALID_AGENT_OUTPUT");
    }

    @Test void allocationsMustBeUniqueAndTotalExactlyOneHundred() {
        var input = input(); var evidence = List.of(input.reference(LEG.sourceId()), input.reference(ExpenseDraftAssistInput.CATALOG));
        var half = new ExpenseDraftSuggestion.Allocation("IT", null, new BigDecimal("50"));
        assertCode(() -> new ExpenseDraftSuggestion.Line("line1", 1, "HOTEL", ExpenseLine.Unit.NIGHT, "住宿", List.of(half), evidence), "INVALID_AGENT_OUTPUT");
        assertCode(() -> new ExpenseDraftSuggestion.Line("line1", 1, "HOTEL", ExpenseLine.Unit.NIGHT, "住宿", List.of(half, half), evidence), "INVALID_AGENT_OUTPUT");
    }

    @Test void persistedStageCannotClaimConfirmationWithoutTheOriginalReview() {
        var run = completed(); var s = run.state();
        var corrupt = new ExpenseDraftAssistRun.State(ExpenseDraftAssistRun.Status.CONFIRMED, 4, s.startedAt(), s.leaseUntil(), s.completedAt(), s.suggestion(), null, null);
        assertThatThrownBy(() -> ExpenseDraftAssistRun.restore(run.context(), corrupt)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "missing-source", "duplicate-source", "unknown-city", "duplicate-leg", "oversized"})
    void rejectsInvalidSourceSnapshots(String variant) {
        var base = input(); var sources = new ArrayList<>(base.sources()); var legs = new ArrayList<>(base.itinerary());
        if (variant.equals("digest")) sources.set(0, new AssistModelPort.Source(new AssistInput.Reference(ExpenseDraftAssistInput.BRIEF, "f".repeat(64)), "输入", "内容"));
        if (variant.equals("missing-source")) sources.remove(0);
        if (variant.equals("duplicate-source")) sources.set(0, sources.get(1));
        if (variant.equals("unknown-city")) legs.set(0, new ExpenseDraftAssistInput.Leg(1, LEG.startsOn(), LEG.endsOn(), "UNKNOWN", "现场调研"));
        if (variant.equals("duplicate-leg")) legs.add(LEG);
        if (variant.equals("oversized")) sources.set(0, source(ExpenseDraftAssistInput.BRIEF, "字".repeat(ExpenseDraftAssistInput.MAX_INPUT_BYTES / 3 + 1)));
        assertCode(() -> new ExpenseDraftAssistInput(base.reportId(), base.applicationId(), 3, 5, base.legalEntityId(), base.reportType(),
                base.catalogVersion(), base.validUntil(), base.financeTargetDigest(), legs, base.options(), sources), "INVALID_AGENT_INPUT");
    }

    private static ExpenseDraftAssistRun.Selection selection() { return new ExpenseDraftAssistRun.Selection("line1", Set.of(ExpenseDraftAssistRun.Part.ITINERARY)); }
    private static ExpenseDraftAssistRun completed() {
        var run = run(); run.start(1, AT, AT.plusSeconds(60)); run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2)); return run;
    }
    private static ExpenseDraftAssistRun run() { return new ExpenseDraftAssistRun(new ExpenseDraftAssistRun.Context(UUID.randomUUID(), "demo", "alice", AT, input(), "a".repeat(64))); }
    private static ExpenseDraftAssistInput input() {
        var options = new ExpenseDraftAssistInput.Options(List.of(new FinanceCatalog.Category("HOTEL", "住宿", List.of(ExpenseLine.Unit.NIGHT))),
                List.of(new ExpenseDraftAssistInput.Choice("IT", "研发")), List.of(new ExpenseDraftAssistInput.Choice("P01", "调研项目")), List.of(new ExpenseDraftAssistInput.Choice("SH", "上海")));
        return new ExpenseDraftAssistInput(UUID.randomUUID(), UUID.randomUUID(), 3, 5, UUID.randomUUID(), ExpenseContent.Type.TRAVEL, "catalog-v1", AT.plusSeconds(300),
                "b".repeat(64), List.of(LEG), options, List.of(source(ExpenseDraftAssistInput.BRIEF, "请整理住宿，全部归调研项目"),
                        source(ExpenseDraftAssistInput.CATALOG, "HOTEL;NIGHT;IT;P01;SH"), source(LEG.sourceId(), "2026-10-01至2026-10-03上海现场调研")));
    }
    private static ExpenseDraftSuggestion suggestion(ExpenseDraftAssistInput input) {
        return new ExpenseDraftSuggestion("synthetic", "v1", ExpenseDraftAssistRun.PROMPT_VERSION,
                List.of(new ExpenseDraftSuggestion.Line("line1", 1, "HOTEL", ExpenseLine.Unit.NIGHT, "现场调研住宿",
                        List.of(new ExpenseDraftSuggestion.Allocation("IT", "P01", new BigDecimal("100"))),
                        List.of(input.reference(LEG.sourceId()), input.reference(ExpenseDraftAssistInput.CATALOG)))));
    }
    private static AssistModelPort.Source source(String id, String value) {
        try { return new AssistModelPort.Source(new AssistInput.Reference(id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)))), "合成来源", value); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, value -> assertThat(value.code()).isEqualTo(code));
    }
}
