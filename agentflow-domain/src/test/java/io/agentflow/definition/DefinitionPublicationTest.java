package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 发布事实的说明边界、权限依据与不可变摘要。
 * @author owlzhangfq@gmail.com
 */
class DefinitionPublicationTest {
    @Test
    void recordsActualPublisherAndOnlyImplementedChecks() {
        var publication = prepare("  首次启用\n供请假申请使用。  ", Set.of("PROCESS_ADMIN"));
        assertThat(publication.changeNote()).isEqualTo("首次启用\n供请假申请使用。");
        assertThat(publication.publishedBy()).isEqualTo("publisher");
        assertThat(publication.authorizedRole()).isEqualTo("PROCESS_ADMIN");
        assertThat(publication.definitionVersion()).isEqualTo(1);
        assertThat(publication.validation().formBound()).isFalse();
        assertThat(publication.validation().checks()).containsExactly(DefinitionPublication.Check.GRAPH_STRUCTURE,
                DefinitionPublication.Check.ASSIGNEE_SYNTAX, DefinitionPublication.Check.RESTRICTED_CONDITIONS);
    }

    @Test
    void requiresNoteAndRejectsOverflowWithoutSilentlyTruncating() {
        for (String note : new String[]{null, "", " \n\t", "说".repeat(2001)}) {
            assertThatThrownBy(() -> prepare(note, Set.of("ADMIN"))).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.code()).isEqualTo("INVALID_PUBLICATION_NOTE"));
        }
        assertThat(prepare("说".repeat(2000), Set.of("ADMIN")).changeNote()).hasSize(2000);
    }

    @Test
    void rejectsPublisherWithoutDefinitionManagementPermission() {
        assertThatThrownBy(() -> prepare("首次发布", Set.of("FINANCE"))).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void validationCheckListCannotBeChangedAfterRecording() {
        var checks = new ArrayList<>(List.of(DefinitionPublication.Check.GRAPH_STRUCTURE));
        var summary = new DefinitionPublication.ValidationSummary(2, 1, 0, false, checks);
        checks.clear();
        assertThat(summary.checks()).containsExactly(DefinitionPublication.Check.GRAPH_STRUCTURE);
        assertThatThrownBy(() -> summary.checks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private DefinitionPublication prepare(String note, Set<String> roles) {
        var graph = new Graph(List.of(new Node("start", "开始", NodeType.START, Map.of()),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("next", "start", "end", "")));
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "leave", "请假", graph);
        return DefinitionPublication.prepare(draft, 1, new Actor("demo", "publisher", roles), note, Instant.parse("2026-09-22T00:00:00Z"));
    }
}
