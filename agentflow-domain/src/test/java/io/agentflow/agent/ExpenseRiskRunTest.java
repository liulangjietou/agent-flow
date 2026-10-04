package io.agentflow.agent;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 覆盖风险解释的多份原证据绑定、当前版本采纳及不可重发的生命周期；任务授权由服务集成测试另验。
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskRunTest {
    private static final Instant AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final String CONCERN = "expense:risk[1]";
    private static final String DIGEST = "a".repeat(64);

    @Test void authorizedCurrentReviewerMayDifferFromRequesterAndReviewDoesNotModifyInputOrOutput() {
        var run = completed(); var original = run.state().suggestion(); var input = run.context().input();
        var selected = new ArrayList<>(List.of(CONCERN));
        run.adopt(3, input, "current-reviewer", selected, "已核对两单原始行程", AT.plusSeconds(3)); selected.clear();
        assertThat(run.state().status()).isEqualTo(ExpenseRiskRun.Status.ADOPTED);
        assertThat(run.state().review().actor()).isEqualTo("current-reviewer");
        assertThat(run.state().review().selectedConcernIds()).containsExactly(CONCERN);
        assertThat(run.context().input()).isSameAs(input); assertThat(run.state().suggestion()).isSameAs(original);
        assertThat(ExpenseRiskRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
        assertThatThrownBy(() -> run.state().review().selectedConcernIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"application-version", "financial-version", "round", "snapshot", "lines", "calendar", "source"})
    void anyChangedOriginPreventsAdoptionWithoutChangingTheRecordedSuggestion(String change) {
        var run = completed(); var input = run.context().input(); var before = run.state(); var old = input.documents().get(1);
        var replacement = new ExpenseRiskInput.Document(old.ordinal(), old.reportId(), old.applicationId(),
                old.applicationVersion() + (change.equals("application-version") ? 1 : 0), old.roundNo() + (change.equals("round") ? 1 : 0),
                old.financialVersion() + (change.equals("financial-version") ? 1 : 0), change.equals("snapshot") ? "b".repeat(64) : old.snapshotDigest(),
                change.equals("lines") ? List.of(2) : old.lineNos());
        var sources = new ArrayList<>(input.sources()); if (change.equals("source")) sources.set(0, source(sources.get(0).reference().sourceId(), "b".repeat(64)));
        var changed = new ExpenseRiskInput(List.of(input.documents().get(0), replacement),
                change.equals("calendar") ? new ExpenseRiskInput.CalendarReference(UUID.randomUUID(), 1, DIGEST) : null, input.concerns(), sources);
        error("AGENT_INPUT_CHANGED", () -> run.adopt(3, changed, "reviewer", List.of(CONCERN), null, AT.plusSeconds(3)));
        assertThat(run.state()).isEqualTo(before);
        run.dismiss(3, "reviewer", "来源已变化，放弃本次解释", AT.plusSeconds(4));
        assertThat(run.state().suggestion()).isEqualTo(before.suggestion());
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "unknown", "missing-own", "missing-document", "missing-coverage", "kind", "missing-item", "extra-item", "prompt"})
    void refusesForgedIncompleteOrReclassifiedEvidence(String problem) {
        var run = run(); var input = run.context().input(); run.start(1, AT, AT.plusSeconds(60));
        var references = new ArrayList<>(input.sources().stream().map(AssistModelPort.Source::reference).toList());
        switch (problem) {
            case "digest" -> references.set(0, new AssistInput.Reference(references.get(0).sourceId(), "b".repeat(64)));
            case "unknown" -> references.add(new AssistInput.Reference("expense:document[3]", DIGEST));
            case "missing-own" -> references.removeIf(reference -> reference.sourceId().equals(CONCERN));
            case "missing-document" -> references.removeIf(reference -> reference.sourceId().equals("expense:document[2]"));
            case "missing-coverage" -> references.removeIf(reference -> reference.sourceId().equals(ExpenseRiskInput.COVERAGE_SOURCE));
            default -> { }
        }
        var items = new ArrayList<ExpenseRiskSuggestion.Item>();
        items.add(item(problem.equals("missing-item") ? "expense:risk[2]" : CONCERN,
                problem.equals("kind") ? ExpenseRiskInput.Kind.SAME_DAY : ExpenseRiskInput.Kind.CROSS_DOCUMENT, references));
        if (problem.equals("extra-item")) items.add(item("expense:risk[2]", ExpenseRiskInput.Kind.CROSS_DOCUMENT, references));
        var suggestion = new ExpenseRiskSuggestion("fixture", "model-v1", problem.equals("prompt") ? "different" : ExpenseRiskRun.PROMPT_VERSION, items);
        error("INVALID_AGENT_OUTPUT", () -> run.complete(2, suggestion, AT.plusSeconds(1)));
        assertThat(run.state().status()).isEqualTo(ExpenseRiskRun.Status.RUNNING);
    }

    @Test void requiresEvidenceLimitationsAndHumanChecksInsteadOfAnUnqualifiedRiskLabel() {
        var references = input().sources().stream().map(AssistModelPort.Source::reference).toList();
        error("INVALID_AGENT_OUTPUT", () -> new ExpenseRiskSuggestion.Item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "有两单", " ", List.of("核对"), references));
        error("INVALID_AGENT_OUTPUT", () -> new ExpenseRiskSuggestion.Item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "有两单", "只比较已选单据", List.of(), references));
        error("INVALID_AGENT_OUTPUT", () -> new ExpenseRiskSuggestion.Item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, "x".repeat(1001), "已选范围", List.of("核对"), references));
    }

    @Test void exactLeaseBoundaryCannotCompleteAndNoTerminalTransitionCanResend() {
        var run = run(); run.start(1, AT, AT.plusSeconds(30));
        error("AGENT_RUN_STATE_CONFLICT", () -> run.start(2, AT.plusSeconds(1), AT.plusSeconds(60)));
        error("AGENT_RUN_STATE_CONFLICT", () -> run.complete(2, suggestion(run.context().input()), AT.plusSeconds(30)));
        run.fail(2, AssistRun.Failure.MODEL_TIMEOUT, AT.plusSeconds(30));
        error("AGENT_RUN_STATE_CONFLICT", () -> run.complete(3, suggestion(run.context().input()), AT.plusSeconds(31)));
        error("AGENT_RUN_STATE_CONFLICT", () -> run.start(3, AT.plusSeconds(31), AT.plusSeconds(60)));
        assertThat(ExpenseRiskRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
    }

    @Test void rejectsStaleVersionsBackwardsTimeAndUnselectedOrDuplicateReviews() {
        var run = completed(); var before = run.state();
        error("CONCURRENCY_CONFLICT", () -> run.dismiss(2, "reviewer", null, AT.plusSeconds(3)));
        error("INVALID_AGENT_TIME", () -> run.dismiss(3, "reviewer", null, AT.plusSeconds(1)));
        error("INVALID_AGENT_REVIEW", () -> run.adopt(3, run.context().input(), "reviewer", List.of(CONCERN, CONCERN), null, AT.plusSeconds(3)));
        error("INVALID_AGENT_REVIEW", () -> run.adopt(3, run.context().input(), "reviewer", List.of("expense:risk[2]"), null, AT.plusSeconds(3)));
        error("INVALID_AGENT_REVIEW", () -> run.adopt(3, run.context().input(), "reviewer", List.of(), null, AT.plusSeconds(3)));
        error("INVALID_AGENT_REVIEW", () -> run.dismiss(3, " ", null, AT.plusSeconds(3)));
        assertThat(run.state()).isEqualTo(before);
        run.dismiss(3, "reviewer", null, AT.plusSeconds(3));
        error("AGENT_RUN_STATE_CONFLICT", () -> run.adopt(4, run.context().input(), "reviewer", List.of(CONCERN), null, AT.plusSeconds(4)));
    }

    @Test void sourceScopeMustBeExplicitAndCannotOmitComparisonDocumentsOrCoverage() {
        var input = input();
        for (var omitted : input.sources()) {
            error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(input.documents(), null, input.concerns(),
                    input.sources().stream().filter(source -> !source.equals(omitted)).toList()));
        }
        var extra = new ArrayList<>(input.sources()); extra.add(source("expense:document[3]", DIGEST));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(input.documents(), null, input.concerns(), extra));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput.Concern(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, List.of(1)));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(input.documents(), null,
                List.of(new ExpenseRiskInput.Concern(CONCERN, ExpenseRiskInput.Kind.NON_WORKING_DAY, List.of(1))), input.sources()));
    }

    @Test void duplicateIdentityAndNonContiguousDocumentOrdinalsCannotExpandTheSelection() {
        var input = input(); var first = input.documents().get(0); var second = input.documents().get(1);
        var duplicate = new ExpenseRiskInput.Document(2, first.reportId(), second.applicationId(), 1, 1, 1, DIGEST, List.of(1));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(List.of(first, duplicate), null, input.concerns(), input.sources()));
        var duplicateApplication = new ExpenseRiskInput.Document(2, second.reportId(), first.applicationId(), 1, 1, 1, DIGEST, List.of(1));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(List.of(first, duplicateApplication), null, input.concerns(), input.sources()));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskInput(List.of(second, first), null, input.concerns(), input.sources()));
    }

    @Test void copiesSourcesAndSelectionListsAndRestoresEveryRealLifecycleState() {
        var original = input(); var docs = new ArrayList<>(original.documents()); var sources = new ArrayList<>(original.sources());
        var input = new ExpenseRiskInput(docs, null, original.concerns(), sources); docs.clear(); sources.clear();
        assertThat(input.documents()).hasSize(2); assertThat(input.sources()).hasSize(4);
        var run = run(); assertRestores(run); run.start(1, AT, AT.plusSeconds(60)); assertRestores(run);
        run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2)); assertRestores(run);
        run.dismiss(3, "reviewer", null, AT.plusSeconds(3)); assertRestores(run);
        var state = run.state();
        assertThatThrownBy(() -> ExpenseRiskRun.restore(run.context(), new ExpenseRiskRun.State(state.status(), 8,
                state.startedAt(), state.leaseUntil(), state.completedAt(), state.suggestion(), null, state.review()))).isInstanceOf(IllegalStateException.class);
    }

    private static ExpenseRiskInput input() {
        var documents = List.of(document(1), document(2));
        return new ExpenseRiskInput(documents, null, List.of(new ExpenseRiskInput.Concern(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, List.of(1, 2))),
                List.of(source("expense:document[1]", DIGEST), source("expense:document[2]", DIGEST), source(ExpenseRiskInput.COVERAGE_SOURCE, DIGEST), source(CONCERN, DIGEST)));
    }
    private static ExpenseRiskInput.Document document(int ordinal) { return new ExpenseRiskInput.Document(ordinal, UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1, DIGEST, List.of(1)); }
    private static AssistModelPort.Source source(String id, String digest) { return new AssistModelPort.Source(new AssistInput.Reference(id, digest), "合成来源", "{}"); }
    private static ExpenseRiskRun run() { return new ExpenseRiskRun(new ExpenseRiskRun.Context(UUID.randomUUID(), "demo", "requester", "task-1", AT, input(), DIGEST)); }
    private static ExpenseRiskRun completed() { var run = run(); run.start(1, AT, AT.plusSeconds(60)); run.complete(2, suggestion(run.context().input()), AT.plusSeconds(2)); return run; }
    private static ExpenseRiskSuggestion suggestion(ExpenseRiskInput input) {
        return new ExpenseRiskSuggestion("fixture", "model-v1", ExpenseRiskRun.PROMPT_VERSION,
                List.of(item(CONCERN, ExpenseRiskInput.Kind.CROSS_DOCUMENT, input.sources().stream().map(AssistModelPort.Source::reference).toList())));
    }
    private static ExpenseRiskSuggestion.Item item(String id, ExpenseRiskInput.Kind kind, List<AssistInput.Reference> references) {
        return new ExpenseRiskSuggestion.Item(id, kind, "两份所选单据含同类费用", "缺少实际行程及企业制度，不能据此认定拆单", List.of("人工核对费用发生背景"), references);
    }
    private static void assertRestores(ExpenseRiskRun run) { assertThat(ExpenseRiskRun.restore(run.context(), run.state()).state()).isEqualTo(run.state()); }
    private static void error(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
