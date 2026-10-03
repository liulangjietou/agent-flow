package io.agentflow.definition;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 发起开关与发布内容分离，状态守卫只属于聚合。
 * @author owlzhangfq@gmail.com
 */
class DefinitionAvailabilityTest {
    @Test
    void disableAndRestoreKeepPublishedIdentityAndContents() {
        var definition = draft(); var graph = definition.graph();
        definition.publish(0, 1);
        definition.changeAvailability(1, false);
        assertThat(definition.status()).isEqualTo(DraftStatus.PUBLISHED);
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.revision()).isEqualTo(2);
        assertThat(definition.graph()).isSameAs(graph);
        assertCode(definition::requireStartEnabled, "DEFINITION_DISABLED");
        assertCode(() -> definition.update("变更", graph, 2), "DEFINITION_IMMUTABLE");
        definition.changeAvailability(2, true);
        assertThat(definition.startEnabled()).isTrue();
        assertThat(definition.revision()).isEqualTo(3);
        assertThatCode(definition::requireStartEnabled).doesNotThrowAnyException();
    }

    @Test
    void rejectsDraftStaleRevisionAndRedundantTransition() {
        var definition = draft();
        assertCode(() -> definition.changeAvailability(0, false), "DEFINITION_NOT_PUBLISHED");
        definition.publish(0, 1);
        assertCode(() -> definition.changeAvailability(0, false), "CONCURRENCY_CONFLICT");
        assertCode(() -> definition.changeAvailability(1, true), "DEFINITION_AVAILABILITY_UNCHANGED");
        assertThat(definition.startEnabled()).isTrue();
        assertThat(definition.revision()).isEqualTo(1);
    }

    private DefinitionDraft draft() {
        return DefinitionDraft.create(UUID.randomUUID(), "tenant-a", "leave", "请假", new Graph(List.of(), List.of()));
    }
    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
