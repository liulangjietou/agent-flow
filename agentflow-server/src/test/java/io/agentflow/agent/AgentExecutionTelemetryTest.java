package io.agentflow.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量不会泄漏到下一运行；业务校验失败与传输成功分别记录，持久化失败不重发模型。
 * @author owlzhangfq@gmail.com
 */
class AgentExecutionTelemetryTest {
    @Test void reportedZeroIsKeptAndThreadScopeDoesNotLeakToNextRun() {
        var repository = repository(); var telemetry = new AgentExecutionTelemetry(repository);
        telemetry.execute("tenant", "alice", AgentExecutionUsage.Kind.PRECHECK_EXPLANATION, UUID.randomUUID(), Instant.now(), () -> {
            AgentExecutionTelemetry.received("v1", new OpenAiTextClient.Reply("provider", "model", JsonNodeFactory.instance.objectNode(),
                    new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, 0L, 0L, 0L))); return "result";
        });
        var first = ArgumentCaptor.forClass(AgentExecutionUsage.class); verify(repository).finish(eq("tenant"), first.capture());
        assertThat(first.getValue().totalTokens()).isZero(); assertThat(first.getValue().outcome()).isEqualTo("SUCCEEDED");
        var second = repository();
        new AgentExecutionTelemetry(second).execute("tenant", "alice", AgentExecutionUsage.Kind.DRAFT, UUID.randomUUID(), Instant.now(), () -> "result");
        var next = ArgumentCaptor.forClass(AgentExecutionUsage.class); verify(second).finish(eq("tenant"), next.capture());
        assertThat(next.getValue().totalTokens()).isNull(); assertThat(next.getValue().providerId()).isNull();
        assertThat(next.getValue().usageStatus()).isEqualTo("NOT_REPORTED");
    }

    @Test void invalidBusinessOutputDoesNotLoseProviderReportedUsage() {
        var repository = repository(); var telemetry = new AgentExecutionTelemetry(repository);
        assertThatThrownBy(() -> telemetry.execute("tenant", "alice", AgentExecutionUsage.Kind.DRAFT, UUID.randomUUID(), Instant.now(), () -> {
            AgentExecutionTelemetry.received("v1", new OpenAiTextClient.Reply("provider", "model", JsonNodeFactory.instance.objectNode(),
                    new OpenAiTextClient.Usage(OpenAiTextClient.UsageStatus.REPORTED, 3L, 4L, 7L)));
            throw new AssistModelPort.ModelFailure(AssistRun.Failure.INVALID_MODEL_OUTPUT);
        })).isInstanceOf(AssistModelPort.ModelFailure.class);
        var saved = ArgumentCaptor.forClass(AgentExecutionUsage.class); verify(repository).finish(eq("tenant"), saved.capture());
        assertThat(saved.getValue().outcome()).isEqualTo("INVALID_MODEL_OUTPUT"); assertThat(saved.getValue().totalTokens()).isEqualTo(7);
    }

    @Test void observationWriteFailureDoesNotReplaceSuccessfulBusinessResult() {
        var repository = repository(); doThrow(new IllegalStateException("database unavailable")).when(repository).finish(any(), any());
        assertThat(new AgentExecutionTelemetry(repository).execute("tenant", "alice", AgentExecutionUsage.Kind.DRAFT, UUID.randomUUID(), Instant.now(), () -> "only call")).isEqualTo("only call");
    }

    private static AgentUsageRepository repository() {
        var repository = mock(AgentUsageRepository.class);
        when(repository.begin(any(), any(), any(), any(), any())).thenAnswer(call -> {
            Instant start = call.getArgument(4);
            return new AgentExecutionUsage(call.getArgument(3), call.getArgument(2), UUID.randomUUID(), start.minusSeconds(2), start,
                    null, 2000L, null, "IN_PROGRESS", null, null, null, "NOT_REPORTED", null, null, null);
        });
        return repository;
    }
}
