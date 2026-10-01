package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.organization.LocalOrganizationDirectory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 任职要求只来自实际固定版本，不依赖该版本是否最新或当前启用，也不授予审批资格。
 * @author owlzhangfq@gmail.com
 */
class DefinitionInitiatorRequirementsTest {
    private final DefinitionDraftRepository definitions = mock(DefinitionDraftRepository.class);
    private final DefinitionInitiatorRequirements requirements = new DefinitionInitiatorRequirements(definitions);

    @Test
    void findsContextualRulesAcrossSeveralFixedVersions() {
        when(definitions.findPublished("demo", "middle", 2)).thenReturn(Optional.of(definition("middle", 2, graph(call("next", "leaf", 1)))));
        when(definitions.findPublished("demo", "leaf", 1)).thenReturn(Optional.of(definition("leaf", 1, graph(new Node("approve", "主管审批",
                NodeType.USER_TASK, Map.of("assigneeRule", LocalOrganizationDirectory.SUPERVISOR_RULE + "1"))))));
        assertThat(requirements.required("demo", graph(call("first", "middle", 2)))).isTrue();
        verify(definitions).findPublished("demo", "middle", 2);
        verify(definitions).findPublished("demo", "leaf", 1);
        verifyNoMoreInteractions(definitions);
    }

    @Test
    void sharedStaticVersionsAreReadOnlyOnce() {
        when(definitions.findPublished("demo", "leaf", 1)).thenReturn(Optional.of(definition("leaf", 1, graph())));
        assertThat(requirements.required("demo", graph(call("left", "leaf", 1), call("right", "leaf", 1)))).isFalse();
        verify(definitions, times(1)).findPublished("demo", "leaf", 1);
    }

    @Test
    void cyclesAndMissingTenantVersionsFailInsteadOfGuessingAnAppointment() {
        when(definitions.findPublished("demo", "leaf", 1)).thenReturn(Optional.of(definition("leaf", 1, graph(call("back", "leaf", 1)))));
        assertThatThrownBy(() -> requirements.required("demo", graph(call("first", "leaf", 1))))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUBPROCESS_RECURSION_FORBIDDEN"));
        assertThatThrownBy(() -> requirements.required("foreign", graph(call("first", "leaf", 1))))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SUBPROCESS_DEFINITION_UNAVAILABLE"));
    }

    @Test
    void directContextualCopyDoesNotRequireLoadingUnrelatedDefinitions() {
        var graph = graph(new Node("copy", "负责人抄送", NodeType.COPY, Map.of("recipientRule", LocalOrganizationDirectory.SUPERVISOR_RULE + "1")));
        assertThat(requirements.required("demo", graph)).isTrue();
        verifyNoInteractions(definitions);
    }

    private DefinitionDraft definition(String key, long version, Graph graph) {
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", key, "子审批", graph); draft.publish(0, version); return draft;
    }
    private Graph graph(Node... nodes) { return new Graph(List.of(nodes), List.of()); }
    private Node call(String id, String key, long version) { return new Node(id, "子调用", NodeType.SUB_PROCESS, new SubprocessPolicy(key, version, Map.of()).properties()); }
}
