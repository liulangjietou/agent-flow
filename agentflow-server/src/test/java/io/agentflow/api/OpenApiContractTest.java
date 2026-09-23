package io.agentflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.auth.AuthService;
import io.agentflow.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 对照实际 Spring 路由与请求记录类型，新增接口或字段不能默默遗漏契约。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:openapi-contract;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class OpenApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JsonUtil json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private JsonNode document() throws Exception {
        try (var in = new ClassPathResource("api/openapi.json").getInputStream()) {
            return json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
        }
    }

    @Test
    void operationsExactlyMatchActualApiRoutesAndRequestFields() throws Exception {
        JsonNode spec = document();
        Set<String> actual = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/v1/")) continue;
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    actual.add(method.name().toLowerCase() + " " + path);
                    JsonNode operation = spec.path("paths").path(path).path(method.name().toLowerCase());
                    assertThat(operation.isMissingNode()).as("contract for %s %s", method, path).isFalse();
                    for (var param : handler.getMethod().getParameters()) {
                        if (param.getAnnotation(RequestBody.class) == null || !param.getType().isRecord()) continue;
                        JsonNode schema = resolve(spec, operation.path("requestBody").path("content").path("application/json").path("schema"));
                        Set<String> fields = new HashSet<>(); schema.path("properties").fieldNames().forEachRemaining(fields::add);
                        assertThat(fields).as("request fields for %s", path).isEqualTo(Arrays.stream(param.getType().getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
                    }
                }
            }
        });
        Set<String> documented = new HashSet<>();
        spec.path("paths").fields().forEachRemaining(path -> path.getValue().fieldNames()
                .forEachRemaining(method -> documented.add(method + " " + path.getKey())));
        assertThat(documented).isEqualTo(actual);
    }

    @Test
    void responseSchemasMatchPublishedRecordFields() throws Exception {
        JsonNode spec = document();
        // ResponseEntity<String> 的真实 JSON 来自业务 DTO，不能把它描述为普通字符串。
        var types = java.util.Map.ofEntries(
                java.util.Map.entry("BusinessCalendar", io.agentflow.calendar.BusinessCalendarController.CalendarResponse.class),
                java.util.Map.entry("CalendarSummary", io.agentflow.calendar.BusinessCalendarRepository.Summary.class),
                java.util.Map.entry("CalendarRules", io.agentflow.calendar.CalendarRules.class),
                java.util.Map.entry("CalendarPeriod", io.agentflow.calendar.CalendarRules.Period.class),
                java.util.Map.entry("CalendarOverride", io.agentflow.calendar.CalendarRules.DayOverride.class),
                java.util.Map.entry("CalendarPage", io.agentflow.calendar.BusinessCalendarService.CalendarPage.class),
                java.util.Map.entry("CalendarVersionPage", io.agentflow.calendar.BusinessCalendarService.VersionPage.class),
                java.util.Map.entry("CalendarCalculation", io.agentflow.calendar.BusinessCalendarService.Calculation.class),
                java.util.Map.entry("CalendarDeadline", io.agentflow.calendar.BusinessDeadline.Result.class),
                java.util.Map.entry("FirstWorkflowReport", io.agentflow.onboarding.FirstWorkflowReadPort.Report.class),
                java.util.Map.entry("FirstWorkflowDefinition", io.agentflow.onboarding.FirstWorkflowReadPort.Definition.class),
                java.util.Map.entry("FirstWorkflowEvidence", io.agentflow.onboarding.FirstWorkflowReadPort.Evidence.class),
                java.util.Map.entry("OperationsReport", io.agentflow.approval.operations.ApprovalOperationsReadPort.Report.class),
                java.util.Map.entry("OperationsMetrics", io.agentflow.approval.operations.ApprovalOperationsReadPort.Metrics.class),
                java.util.Map.entry("OperationsDaily", io.agentflow.approval.operations.ApprovalOperationsReadPort.Daily.class),
                java.util.Map.entry("OperationsProcess", io.agentflow.approval.operations.ApprovalOperationsReadPort.ProcessSummary.class),
                java.util.Map.entry("OperationsWaitingNode", io.agentflow.approval.operations.ApprovalOperationsReadPort.WaitingNode.class),
                java.util.Map.entry("OperationsWaitingTask", io.agentflow.approval.operations.ApprovalOperationsReadPort.WaitingTask.class),
                java.util.Map.entry("AssigneeOption", io.agentflow.definition.DefinitionAssigneeDirectory.Option.class),
                java.util.Map.entry("ApplicationComment", io.agentflow.approval.comment.ApplicationComment.class),
                java.util.Map.entry("CommentPage", io.agentflow.approval.comment.ApplicationCommentService.Page.class),
                java.util.Map.entry("Application", io.agentflow.approval.ApplicationResponse.class),
                java.util.Map.entry("SubmissionRound", io.agentflow.approval.SubmissionRoundResponse.class),
                java.util.Map.entry("Definition", io.agentflow.definition.DefinitionController.DefinitionResponse.class),
                java.util.Map.entry("PublicationResult", io.agentflow.definition.DefinitionController.PublicationResponse.class),
                java.util.Map.entry("Publication", io.agentflow.definition.DefinitionPublication.class),
                java.util.Map.entry("PublicationValidation", io.agentflow.definition.DefinitionPublication.ValidationSummary.class),
                java.util.Map.entry("Task", io.agentflow.approval.process.FlowableTaskFacade.TaskView.class),
                java.util.Map.entry("CountersignProgress", io.agentflow.approval.model.CountersignProgress.class),
                java.util.Map.entry("TaskActionResult", io.agentflow.approval.process.FlowableTaskFacade.ActionResult.class),
                java.util.Map.entry("PendingTask", io.agentflow.approval.workspace.PendingTaskReadPort.Item.class),
                java.util.Map.entry("PendingTaskPage", io.agentflow.approval.workspace.PendingTaskController.Page.class),
                java.util.Map.entry("WorkspaceApplication", io.agentflow.approval.workspace.WorkspaceReadPort.ApplicationItem.class),
                java.util.Map.entry("HandledItem", io.agentflow.approval.workspace.WorkspaceReadPort.HandledItem.class),
                java.util.Map.entry("InboxMessage", io.agentflow.notification.InboxMessage.class),
                java.util.Map.entry("InboxPage", io.agentflow.notification.InboxApplicationService.Page.class),
                java.util.Map.entry("HistoryEvent", io.agentflow.approval.history.HistoryEvent.class),
                java.util.Map.entry("HistoryPage", io.agentflow.approval.history.HistoryPage.class),
                java.util.Map.entry("LoginResponse", io.agentflow.auth.AuthController.LoginResponse.class),
                java.util.Map.entry("CurrentIdentity", io.agentflow.auth.AuthService.LoginResult.class),
                java.util.Map.entry("SimulationResult", io.agentflow.definition.DefinitionSimulator.Result.class),
                java.util.Map.entry("SimulationDecision", io.agentflow.definition.DefinitionSimulator.Decision.class),
                java.util.Map.entry("SimulationBranch", io.agentflow.definition.DefinitionSimulator.Branch.class),
                java.util.Map.entry("ComparisonChange", io.agentflow.definition.DefinitionDiffService.Change.class),
                java.util.Map.entry("ComparisonBaseline", io.agentflow.definition.DefinitionApplicationService.Baseline.class),
                java.util.Map.entry("TemplateCopy", io.agentflow.template.TemplateCopyRepository.CopyView.class),
                java.util.Map.entry("TemplateScenario", io.agentflow.template.ProcessTemplate.Scenario.class),
                java.util.Map.entry("FormField", io.agentflow.form.FormSchema.Field.class),
                java.util.Map.entry("FormOption", io.agentflow.form.FormSchema.Option.class),
                java.util.Map.entry("GraphNode", io.agentflow.definition.DefinitionModels.Node.class),
                java.util.Map.entry("GraphEdge", io.agentflow.definition.DefinitionModels.Edge.class));
        types.forEach((name, type) -> {
            Set<String> fields = new HashSet<>(); spec.path("components").path("schemas").path(name).path("properties").fieldNames().forEachRemaining(fields::add);
            assertThat(fields).as("response fields for %s", name).isEqualTo(Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
        });
    }

    @Test
    void contractRequiresAuthenticationAndContainsNoSessionData() throws Exception {
        mvc.perform(get("/api/v1/openapi.json")).andExpect(status().isUnauthorized());
        String token = auth.login("demo", "alice", "demo").token();
        String body = mvc.perform(get("/api/v1/openapi.json").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("openapi").value("3.1.1"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(token);
        assertThat(json.read(body, JsonNode.class)).isEqualTo(document());
    }

    @Test
    void cancellationIsAReadableApplicationStateWithoutReplacingSubmissionConclusions() throws Exception {
        JsonNode spec = document();
        for (var entry : java.util.Map.of("Application", "status", "WorkspaceApplication", "status", "HandledItem", "applicationStatus").entrySet()) {
            var values = spec.path("components").path("schemas").path(entry.getKey()).path("properties").path(entry.getValue()).path("enum");
            assertThat(java.util.stream.StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText)).contains("CANCELLED");
        }
        var roundValues = spec.path("components").path("schemas").path("SubmissionRound").path("properties").path("status").path("enum");
        assertThat(java.util.stream.StreamSupport.stream(roundValues.spliterator(), false).map(JsonNode::asText)).doesNotContain("CANCELLED");
    }

    private JsonNode resolve(JsonNode spec, JsonNode schema) {
        return schema.has("$ref") ? spec.at(schema.get("$ref").asText().substring(1)) : schema;
    }
}
