package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpensePrecheckJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 解释与财务动作分离，覆盖来源伪造、遗漏、期限、复核和持久状态还原。
 * @author owlzhangfq@gmail.com
 */
class PrecheckExplanationRunTest {
    private static final Instant AT = Instant.parse("2026-10-03T12:00:00Z");
    private static final String ISSUE = "precheck:finding[0]";
    private static final AssistInput.Reference REFERENCE = new AssistInput.Reference(ISSUE, "a".repeat(64));

    @Test void recordsOnlySelectedReviewsAndRestoresWithoutReinterpretingExpiredHistory() {
        var run = run(); var input = run.context().input(); var suggestion = suggestion(REFERENCE);
        run.start(1, AT.plusSeconds(1), AT.plusSeconds(60)); run.complete(2, suggestion, AT.plusSeconds(2));
        var selected = new ArrayList<>(List.of(ISSUE)); run.adopt(3, "alice", selected, "已核对预算来源", AT.plusSeconds(3)); selected.clear();
        assertThat(run.state().status()).isEqualTo(PrecheckExplanationRun.Status.ADOPTED);
        assertThat(run.state().review().selectedIssueIds()).containsExactly(ISSUE);
        assertThat(run.context().input()).isSameAs(input);
        assertThat(run.state().suggestion()).isSameAs(suggestion);
        assertThat(PrecheckExplanationRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
        assertThatThrownBy(() -> run.state().review().selectedIssueIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void adoptionExpiresAtOriginalDeadlineButDismissalRemainsAvailable() {
        var run = completed();
        assertThatThrownBy(() -> run.adopt(3, "alice", List.of(ISSUE), null, AT.plusSeconds(300)))
                .isInstanceOf(DomainException.class).hasMessageContaining("expired");
        assertThat(run.state().version()).isEqualTo(3);
        run.dismiss(3, "alice", null, AT.plusSeconds(600));
        assertThat(run.state().review().selectedIssueIds()).isEmpty();
        assertThat(PrecheckExplanationRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
    }

    @Test void leaseCannotBeExtendedAndLateCompletionCannotReplaceFailure() {
        var run = run(); run.start(1, AT, AT.plusSeconds(30));
        assertThatThrownBy(() -> run.start(2, AT.plusSeconds(1), AT.plusSeconds(60))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.complete(2, suggestion(REFERENCE), AT.plusSeconds(30))).isInstanceOf(DomainException.class);
        run.fail(2, AssistRun.Failure.MODEL_TIMEOUT, AT.plusSeconds(30));
        assertThatThrownBy(() -> run.complete(3, suggestion(REFERENCE), AT.plusSeconds(31))).isInstanceOf(DomainException.class);
        assertThat(PrecheckExplanationRun.restore(run.context(), run.state()).state().failure()).isEqualTo(AssistRun.Failure.MODEL_TIMEOUT);
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "unknown", "missing-own", "missing-issue", "extra-issue", "no-correction", "prompt"})
    void rejectsOutputsNotBoundToExactlyTheSelectedIssues(String problem) {
        var run = run(); run.start(1, AT, AT.plusSeconds(60));
        var refs = switch (problem) {
            case "digest" -> List.of(new AssistInput.Reference(ISSUE, "b".repeat(64)));
            case "unknown" -> List.of(REFERENCE, new AssistInput.Reference("expense:line[2]", "a".repeat(64)));
            case "missing-own" -> List.of(run.context().input().sources().get(0).reference());
            default -> List.of(REFERENCE);
        };
        var items = new ArrayList<PrecheckExplanationSuggestion.Item>();
        items.add(new PrecheckExplanationSuggestion.Item(problem.equals("missing-issue") ? "precheck:result" : ISSUE,
                "预算预检没有满足条件", problem.equals("no-correction") ? List.of() : List.of("核对预算与费用归属"), refs));
        if (problem.equals("extra-issue")) items.add(new PrecheckExplanationSuggestion.Item("precheck:finding[1]", "额外问题", List.of("核对"), refs));
        var value = new PrecheckExplanationSuggestion("fixture", "model-v1", problem.equals("prompt") ? "changed" : PrecheckExplanationRun.PROMPT_VERSION, items);
        assertThatThrownBy(() -> run.complete(2, value, AT.plusSeconds(1))).isInstanceOf(DomainException.class);
        assertThat(run.state().status()).isEqualTo(PrecheckExplanationRun.Status.RUNNING);
    }

    @Test void onlyOwnerCanReviewAndVersionOrUnselectedIssuesCannotBeOverwritten() {
        var run = completed();
        assertThatThrownBy(() -> run.adopt(3, "admin", List.of(ISSUE), null, AT.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.adopt(2, "alice", List.of(ISSUE), null, AT.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.adopt(3, "alice", List.of("precheck:finding[1]"), null, AT.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.adopt(3, "alice", List.of(ISSUE, ISSUE), null, AT.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.adopt(3, "alice", List.of(), null, AT.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThat(run.state().version()).isEqualTo(3);
    }

    @Test void readyExplainsOnlyTheOriginalConclusionWithoutInventingRequiredCorrections() {
        var result = source("precheck:result");
        var input = input(ExpensePrecheckJob.Status.READY, List.of(result));
        var value = new PrecheckExplanationSuggestion("fixture", "model-v1", PrecheckExplanationRun.PROMPT_VERSION,
                List.of(new PrecheckExplanationSuggestion.Item("precheck:result", "原预检就绪，正式提交仍需检查有效性", List.of(), List.of(result.reference()))));
        assertThatCode(() -> value.requireMatches(input)).doesNotThrowAnyException();
        assertThat(input.issueIds()).containsExactly("precheck:result");
        assertThat(input.currentAt(AT.minusNanos(1))).isFalse();
        assertThat(input.currentAt(AT)).isTrue();
        assertThat(input.currentAt(AT.plusSeconds(300))).isFalse();
    }

    @Test void inputRequiresExplicitConclusionAndAtLeastOneSelectedFailureAndCopiesSources() {
        assertThatThrownBy(() -> input(ExpensePrecheckJob.Status.BLOCKED, List.of(source(ISSUE)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> input(ExpensePrecheckJob.Status.BLOCKED, List.of(source("precheck:result")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> input(ExpensePrecheckJob.Status.READY, List.of(source("precheck:result"), source(ISSUE)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> input(ExpensePrecheckJob.Status.BLOCKED, List.of(source("precheck:result"), source(ISSUE), source(ISSUE)))).isInstanceOf(DomainException.class);
        var original = new ArrayList<>(List.of(source("precheck:result"), source(ISSUE)));
        var input = input(ExpensePrecheckJob.Status.UNAVAILABLE, original); original.clear();
        assertThat(input.sources()).hasSize(2);
        assertThatThrownBy(() -> input.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void persistedStateCannotForgeReviewOrLifecycleVersion() {
        var run = completed(); var state = run.state();
        var forged = new PrecheckExplanationRun.State(state.status(), 4, state.startedAt(), state.leaseUntil(), state.completedAt(), state.suggestion(), null, null);
        assertThatThrownBy(() -> PrecheckExplanationRun.restore(run.context(), forged)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PrecheckExplanationRun.Context(UUID.randomUUID(), "demo", "alice", AT.plusSeconds(300), run.context().input(), "a".repeat(64)))
                .isInstanceOf(DomainException.class);
    }

    private static PrecheckExplanationRun run() {
        return new PrecheckExplanationRun(new PrecheckExplanationRun.Context(UUID.randomUUID(), "demo", "alice", AT,
                input(ExpensePrecheckJob.Status.BLOCKED, List.of(source("precheck:result"), source(ISSUE))), "a".repeat(64)));
    }
    private static PrecheckExplanationRun completed() {
        var run = run(); run.start(1, AT, AT.plusSeconds(60)); run.complete(2, suggestion(REFERENCE), AT.plusSeconds(2)); return run;
    }
    private static PrecheckExplanationInput input(ExpensePrecheckJob.Status status, List<AssistModelPort.Source> sources) {
        return new PrecheckExplanationInput(UUID.randomUUID(), UUID.randomUUID(), 1, 1, UUID.randomUUID(), 1, status, AT, AT.plusSeconds(300), sources);
    }
    private static AssistModelPort.Source source(String id) { return new AssistModelPort.Source(new AssistInput.Reference(id, "a".repeat(64)), "合成来源", "{}"); }
    private static PrecheckExplanationSuggestion suggestion(AssistInput.Reference reference) {
        return new PrecheckExplanationSuggestion("fixture", "model-v1", PrecheckExplanationRun.PROMPT_VERSION,
                List.of(new PrecheckExplanationSuggestion.Item(ISSUE, "预算预检未通过", List.of("核对费用归属和可用预算"), List.of(reference))));
    }
}
