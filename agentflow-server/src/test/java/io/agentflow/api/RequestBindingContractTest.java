package io.agentflow.api;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionController;
import io.agentflow.definition.DefinitionInitiatorRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实发布路由验证进入控制器前的参数绑定也遵循统一错误契约。
 * @author owlzhangfq@gmail.com
 */
class RequestBindingContractTest {
    private static final UUID DEFINITION_ID = UUID.fromString("6a62a7d6-660a-4098-aa56-be1fab565a50");
    private static final String PUBLISH_PATH = "/api/v1/process-definitions/" + DEFINITION_ID + "/publish";
    private final DefinitionApplicationService service = mock(DefinitionApplicationService.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final IdempotencyExecutor idempotency = mock(IdempotencyExecutor.class);
    private final DefinitionInitiatorRequirements requirements = mock(DefinitionInitiatorRequirements.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new DefinitionController(service, currentActor, idempotency, requirements))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest
    @NullAndEmptySource
    void missingOrEmptyRevisionReturnsErrorContractBeforeBusinessExecution(String revision) throws Exception {
        var request = post(PUBLISH_PATH);
        if (revision != null) request.param("expectedRevision", revision);
        assertInvalidRequest(mvc.perform(request), PUBLISH_PATH);
        verifyNoInteractions(service, currentActor, idempotency, requirements);
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-invalid-value", "1.5", "9223372036854775808"})
    void malformedRevisionReturnsErrorContractWithoutEchoingSubmittedValue(String revision) throws Exception {
        assertInvalidRequest(mvc.perform(post(PUBLISH_PATH).param("expectedRevision", revision)), PUBLISH_PATH)
                .andExpect(jsonPath("message", not(containsString(revision))));
        verifyNoInteractions(service, currentActor, idempotency, requirements);
    }

    @Test
    void malformedPathIdentifierReturnsTheSameContract() throws Exception {
        String path = "/api/v1/process-definitions/not-a-uuid/publish";
        assertInvalidRequest(mvc.perform(post(path).param("expectedRevision", "0")), path);
        verifyNoInteractions(service, currentActor, idempotency, requirements);
    }

    @Test
    void validParametersStillReachTheExistingDomainErrorMapping() throws Exception {
        var actor = new Actor("demo", "admin", Set.of("ADMIN"));
        when(currentActor.actor()).thenReturn(actor);
        when(idempotency.execute(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Supplier<?>>getArgument(2).get();
            throw new AssertionError("Expected the original domain failure");
        });
        when(service.publish(actor, DEFINITION_ID, 7, "发布复核"))
                .thenThrow(new DomainException("CONCURRENCY_CONFLICT", "Definition revision changed"));

        mvc.perform(post(PUBLISH_PATH).param("expectedRevision", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"changeNote\":\"发布复核\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("CONCURRENCY_CONFLICT"));
        verify(service).publish(actor, DEFINITION_ID, 7, "发布复核");
    }

    @Test
    void malformedJsonKeepsItsExistingErrorContract() throws Exception {
        assertInvalidRequest(mvc.perform(post(PUBLISH_PATH).param("expectedRevision", "0")
                .contentType(MediaType.APPLICATION_JSON).content("{")), PUBLISH_PATH);
        verifyNoInteractions(service, currentActor, idempotency, requirements);
    }

    private ResultActions assertInvalidRequest(ResultActions result, String path) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("message").isNotEmpty())
                .andExpect(jsonPath("traceId").isNotEmpty())
                .andExpect(jsonPath("path").value(path));
    }
}
