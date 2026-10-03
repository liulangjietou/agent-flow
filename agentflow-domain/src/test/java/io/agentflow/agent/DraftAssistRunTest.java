package io.agentflow.agent;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 草稿建议覆盖来源、原版本、部分采纳、不可变证据及单次状态推进边界。
 * @author owlzhangfq@gmail.com
 */
class DraftAssistRunTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final AssistInput.Reference BRIEF = new AssistInput.Reference(DraftAssistInput.BRIEF, "a".repeat(64));
    private static final FormSchema SCHEMA = new FormSchema(1, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXTAREA,
            true, null, 100, null, null, List.of(), null, null)));

    @Test
    void preservesModelValuesWhileHumanSelectsAndEditsOnlyOneField() {
        var run = started(); var suggestion = suggestion("form:reason", "模型草稿", BRIEF);
        run.complete(2, suggestion, NOW.plusSeconds(2));
        run.adopt(3, 7, "alice", List.of(new DraftSuggestion.Selection("form:reason", "人工修改后的草稿")), "核对来源", NOW.plusSeconds(3));
        assertThat(run.state().suggestion()).isEqualTo(suggestion);
        assertThat(run.state().review().selected().get(0).value()).isEqualTo("人工修改后的草稿");
        assertThat(run.state().review().appliedApplicationVersion()).isEqualTo(8);
        assertThat(DraftAssistRun.restore(run.context(), run.state()).state()).isEqualTo(run.state());
        assertThatThrownBy(() -> run.adopt(4, 7, "alice", List.of(new DraftSuggestion.Selection("form:reason", "再次覆盖")), null, NOW.plusSeconds(4)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsForeignEvidenceUnknownFieldsAndInvalidValuesWithoutCompleting() {
        for (var value : List.of(suggestion("form:reason", "伪造引用", new AssistInput.Reference("form:secret", "b".repeat(64))),
                suggestion("form:secret", "隐藏字段", BRIEF), suggestion("form:reason", 123, BRIEF),
                suggestion(DraftAssistInput.TITLE, " ", BRIEF))) {
            var run = started();
            assertThatThrownBy(() -> run.complete(2, value, NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
            assertThat(run.state().status()).isEqualTo(DraftAssistRun.Status.RUNNING);
        }
    }

    @Test
    void changedDraftOrAnotherReviewerCannotAdoptButOriginalOwnerCanDismiss() {
        var run = started(); run.complete(2, suggestion("form:reason", "草稿", BRIEF), NOW.plusSeconds(2));
        var selection = List.of(new DraftSuggestion.Selection("form:reason", "确认值"));
        assertThatThrownBy(() -> run.adopt(3, 8, "alice", selection, null, NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("AGENT_INPUT_CHANGED"));
        assertThatThrownBy(() -> run.adopt(3, 7, "admin", selection, null, NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> run.adopt(3, 7, "alice", List.of(selection.get(0), selection.get(0)), null, NOW.plusSeconds(3)))
                .isInstanceOf(DomainException.class);
        run.dismiss(3, "alice", "草稿已变化", NOW.plusSeconds(3));
        assertThat(run.state().status()).isEqualTo(DraftAssistRun.Status.DISMISSED);
        assertThat(run.state().review().selected()).isNull();
    }

    @Test
    void deepFreezesModelAndHumanTableValuesAndRejectsSensitiveTargets() {
        var rows = new ArrayList<Map<String, String>>(); rows.add(new java.util.HashMap<>(Map.of("item", "原值")));
        var proposal = new DraftSuggestion.Proposal("form:items", rows, List.of(BRIEF));
        var selected = new DraftSuggestion.Selection("form:items", rows);
        rows.get(0).put("item", "后续改写"); rows.clear();
        assertThat(proposal.value()).isEqualTo(List.of(Map.of("item", "原值")));
        assertThat(selected.value()).isEqualTo(proposal.value());
        var secret = new FormSchema.Field("secret", "敏感字段", FormSchema.FieldType.TEXT, false, null, 100, null, null,
                List.of(), null, null, true, Map.of());
        assertThatThrownBy(() -> new DraftAssistInput(UUID.randomUUID(), 1, new FormSchema(1, List.of(secret)), sources()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnproposedTargetsAndKeepsFailureTerminal() {
        var run = started(); run.complete(2, suggestion("form:reason", "草稿", BRIEF), NOW.plusSeconds(2));
        assertThatThrownBy(() -> run.adopt(3, 7, "alice", List.of(new DraftSuggestion.Selection(DraftAssistInput.TITLE, "额外标题")), null, NOW.plusSeconds(3)))
                .isInstanceOf(DomainException.class);
        var failed = started(); failed.fail(2, AssistRun.Failure.MODEL_TIMEOUT, NOW.plusSeconds(2));
        assertThat(DraftAssistRun.restore(failed.context(), failed.state()).state()).isEqualTo(failed.state());
        assertThatThrownBy(() -> failed.complete(3, suggestion("form:reason", "晚到结果", BRIEF), NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
    }

    private DraftAssistRun started() {
        var run = new DraftAssistRun(new DraftAssistRun.Context(UUID.randomUUID(), "demo", "alice", NOW,
                new DraftAssistInput(UUID.randomUUID(), 7, SCHEMA, sources()), "b".repeat(64)));
        run.start(1, NOW.plusSeconds(1)); return run;
    }
    private static List<AssistModelPort.Source> sources() { return List.of(new AssistModelPort.Source(BRIEF, "生成要求", "仅依据这段文字生成")); }
    private static DraftSuggestion suggestion(String target, Object value, AssistInput.Reference reference) {
        return new DraftSuggestion("fixture", "fixture-v1", DraftAssistRun.PROMPT_VERSION, List.of(new DraftSuggestion.Proposal(target, value, List.of(reference))));
    }
}
