package io.agentflow.definition;

import org.junit.jupiter.api.Test;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流程定义用例测试，确认发布和模拟均经过领域服务。
 * @author owlzhangfq@gmail.com
 */
class DefinitionApplicationServiceTest {
    @Test
    void publicationRechecksDirectoryAndLeavesNoPublishedStateWhenMembersDisappear() {
        InMemoryRepository repository = new InMemoryRepository();
        RecordingDeployment deployment = new RecordingDeployment();
        DefinitionAssigneeDirectory directory = mock(DefinitionAssigneeDirectory.class);
        when(directory.options("tenant-a")).thenReturn(List.of(new DefinitionAssigneeDirectory.Option("role:FINANCE", "FINANCE", 1)))
                .thenReturn(List.of(new DefinitionAssigneeDirectory.Option("role:FINANCE", "FINANCE", 0)));
        var service = new DefinitionApplicationService(repository, deployment, mock(DefinitionPublicationRepository.class), directory,
                mock(io.agentflow.calendar.BusinessCalendarRepository.class), mock(io.agentflow.event.EventContractBindings.class));
        var draft = service.create("tenant-a", "directory-change", "目录变化", graph());
        org.assertj.core.api.Assertions.assertThat(service.validate("tenant-a", graph(), null)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publish(
                        new io.agentflow.common.Actor("tenant-a", "admin", java.util.Set.of("ADMIN")), draft.id(), 0, "发布"))
                .isInstanceOf(DefinitionValidationException.class);
        assertThat(draft.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(deployment.received).isNull();
    }

    @Test
    void publishesOnceAndSimulatesUsingSafeCondition() {
        InMemoryRepository repository = new InMemoryRepository();
        RecordingDeployment deployment = new RecordingDeployment();
        DefinitionApplicationService service = new DefinitionApplicationService(repository, deployment, mock(DefinitionPublicationRepository.class),
                tenant -> List.of(new DefinitionAssigneeDirectory.Option("role:FINANCE", "FINANCE", 1)),
                mock(io.agentflow.calendar.BusinessCalendarRepository.class), mock(io.agentflow.event.EventContractBindings.class));
        DefinitionDraft draft = service.create("tenant-a", "travel", "出差审批", graph());

        DefinitionDraft published = service.publish(new io.agentflow.common.Actor("tenant-a", "test-admin", java.util.Set.of("ADMIN")), draft.id(), 0, "集成测试发布");

        assertThat(published.status()).isEqualTo(DraftStatus.PUBLISHED);
        assertThat(deployment.received).isSameAs(published);
        assertThat(service.simulate("tenant-a", draft.id(), new EvaluationContext(Map.of("amount", 1200))))
                .containsExactly("start", "approve", "end");
    }

    @Test
    void writesRestrictedBpmnWithoutExecutableUserExpression() {
        DefinitionDraft draft = DefinitionDraft.create(UUID.randomUUID(), "tenant-a", "travel", "出差审批", graph());

        String bpmn = FlowableDefinitionDeploymentAdapter.RestrictedBpmnWriter.write(draft);

        assertThat(bpmn).contains("flowable:candidateGroups=\"FINANCE\"")
                .contains("conditionExpression xsi:type=\"tFormalExpression\">${flowableConditionEvaluator.matches(execution, '")
                .doesNotContain("${evil}")
                .doesNotContain("amount >= 1000");
    }

    @Test
    void evaluatorDecodesOnlyTheAllowlistedCondition() {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariables()).thenReturn(Map.of("formData", Map.of("amount", 1200)));
        String encoded = java.util.Base64.getEncoder().encodeToString("amount >= 1000".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(new FlowableConditionEvaluator().matches(execution, encoded)).isTrue();
    }

    private Graph graph() {
        return new Graph(List.of(
                new Node("start", "开始", NodeType.START, Map.of()),
                new Node("approve", "审批", NodeType.USER_TASK, Map.of("assigneeRule", "role:FINANCE")),
                new Node("end", "结束", NodeType.END, Map.of())
        ), List.of(new Edge("a", "start", "approve", ""), new Edge("b", "approve", "end", "amount >= 1000")));
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    private static final class InMemoryRepository implements DefinitionDraftRepository {
        private DefinitionDraft value;
        public DefinitionDraft save(DefinitionDraft draft) { value = draft; return draft; }
        public long nextVersion(String tenantId, String key) { return 1L; }
        public Optional<DefinitionDraft> findById(String tenantId, UUID id) {
            return value != null && value.id().equals(id) && value.tenantId().equals(tenantId) ? Optional.of(value) : Optional.empty();
        }
        public Optional<DefinitionDraft> findPublished(String tenantId, String key, long version) {
            return value != null && value.tenantId().equals(tenantId) && value.key().equals(key)
                    && value.version() == version && value.status() == DraftStatus.PUBLISHED ? Optional.of(value) : Optional.empty();
        }
        public Optional<DefinitionDraft> lockPublished(String tenantId, String key, long version) {
            return findPublished(tenantId, key, version);
        }
        public List<DefinitionDraft> findAll(String tenantId, String status) { return List.of(); }
    }

    /**
     * @author owlzhangfq@gmail.com
     */
    private static final class RecordingDeployment implements DefinitionDeploymentPort {
        private DefinitionDraft received;
        public DeploymentResult deploy(DefinitionDraft draft) {
            received = draft;
            return new DeploymentResult("deployment", "definition", draft.key(), draft.version());
        }
    }
}
