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
                java.util.Map.entry("AdvanceRequestView", io.agentflow.expense.AdvanceRequestService.View.class),
                java.util.Map.entry("AdvanceRequestReceipt", io.agentflow.expense.AdvanceRequestService.Receipt.class),
                java.util.Map.entry("AdvanceRequestContent", io.agentflow.expense.AdvanceRequestContent.class),
                java.util.Map.entry("AdvanceRequestRound", io.agentflow.expense.AdvanceRequestRoundView.class),
                java.util.Map.entry("AdvanceRequestApproval", io.agentflow.expense.AdvanceRequest.Approval.class),
                java.util.Map.entry("AdvanceRequestItem", io.agentflow.expense.AdvanceRequestQuery.Item.class),
                java.util.Map.entry("AdvanceRequestPage", io.agentflow.expense.AdvanceRequestQuery.Page.class),
                java.util.Map.entry("AdvanceRequestCheckOptions", io.agentflow.expense.AdvanceRequestCheckService.Options.class),
                java.util.Map.entry("AdvanceRequestCheckSummary", io.agentflow.expense.AdvanceRequestCheckService.Summary.class),
                java.util.Map.entry("AdvanceRequestCheckReceipt", io.agentflow.expense.AdvanceRequestCheckService.Receipt.class),
                java.util.Map.entry("AdvanceRequestCheckPage", io.agentflow.expense.AdvanceRequestCheckService.Page.class),
                java.util.Map.entry("AdvanceRequestCheckView", io.agentflow.expense.AdvanceRequestCheckService.View.class),
                java.util.Map.entry("ExpensePlanView", io.agentflow.expense.ExpensePlanService.View.class),
                java.util.Map.entry("ExpensePlanReceipt", io.agentflow.expense.ExpensePlanService.Receipt.class),
                java.util.Map.entry("ExpensePlanContent", io.agentflow.expense.ExpensePlanContent.class),
                java.util.Map.entry("ExpensePlanLine", io.agentflow.expense.ExpensePlanContent.Line.class),
                java.util.Map.entry("ExpensePlanRound", io.agentflow.expense.ExpensePlanRound.class),
                java.util.Map.entry("ExpensePlanFrozenLine", io.agentflow.expense.ExpensePlanRound.FrozenLine.class),
                java.util.Map.entry("ExpensePlanItem", io.agentflow.expense.ExpensePlanQuery.Item.class),
                java.util.Map.entry("ExpensePlanPage", io.agentflow.expense.ExpensePlanQuery.Page.class),
                java.util.Map.entry("ExpensePlanCheckOptions", io.agentflow.expense.ExpensePlanCheckService.Options.class),
                java.util.Map.entry("ExpensePlanCheckSummary", io.agentflow.expense.ExpensePlanCheckService.Summary.class),
                java.util.Map.entry("ExpensePlanCheckReceipt", io.agentflow.expense.ExpensePlanCheckService.Receipt.class),
                java.util.Map.entry("ExpensePlanCheckPage", io.agentflow.expense.ExpensePlanCheckService.Page.class),
                java.util.Map.entry("ExpensePlanCheckView", io.agentflow.expense.ExpensePlanCheckService.View.class),
                java.util.Map.entry("ExpenseReportItem", io.agentflow.expense.ExpenseWorkspaceQuery.ReportItem.class),
                java.util.Map.entry("ExpenseReportPage", io.agentflow.expense.ExpenseWorkspaceQuery.ReportPage.class),
                java.util.Map.entry("ExpensePriorLine", io.agentflow.expense.ExpenseWorkspaceQuery.PriorLine.class),
                java.util.Map.entry("ExpensePriorItem", io.agentflow.expense.ExpenseWorkspaceQuery.PriorItem.class),
                java.util.Map.entry("ExpensePriorPage", io.agentflow.expense.ExpenseWorkspaceQuery.PriorPage.class),
                java.util.Map.entry("ExpenseAdvanceItem", io.agentflow.expense.ExpenseWorkspaceQuery.AdvanceItem.class),
                java.util.Map.entry("ExpenseAdvancePage", io.agentflow.expense.ExpenseWorkspaceQuery.AdvancePage.class),
                java.util.Map.entry("ExpenseWorkflow", io.agentflow.expense.ExpenseWorkflowQuery.View.class),
                java.util.Map.entry("ExpenseWorkflowPaper", io.agentflow.expense.ExpenseWorkflowQuery.Paper.class),
                java.util.Map.entry("ExpenseWorkflowBudget", io.agentflow.expense.ExpenseWorkflowQuery.Budget.class),
                java.util.Map.entry("ExpenseTaskOptions", io.agentflow.expense.ExpenseWorkflowQuery.TaskOptions.class),
                java.util.Map.entry("ExpenseReductionReceipt", io.agentflow.expense.ExpenseReductionService.Receipt.class),
                java.util.Map.entry("ExpenseSubmissionReceipt", io.agentflow.expense.ExpenseSubmissionService.Receipt.class),
                java.util.Map.entry("ExpensePaperReceipt", io.agentflow.expense.ExpenseApprovalService.Receipt.class),
                java.util.Map.entry("ExpenseLifecycleReceipt", io.agentflow.expense.ExpenseLifecycleService.Receipt.class),
                java.util.Map.entry("ExpensePrecheckReceipt", io.agentflow.expense.ExpensePrecheckService.Receipt.class),
                java.util.Map.entry("ExpensePrecheckOptions", io.agentflow.expense.ExpensePrecheckService.Options.class),
                java.util.Map.entry("ExpensePrecheckSummary", io.agentflow.expense.ExpensePrecheckService.Summary.class),
                java.util.Map.entry("ExpensePrecheckFinding", io.agentflow.expense.ExpensePrecheckJob.Finding.class),
                java.util.Map.entry("ExpensePrecheckView", io.agentflow.expense.ExpensePrecheckService.View.class),
                java.util.Map.entry("ExpensePrecheckPage", io.agentflow.expense.ExpensePrecheckService.Page.class),
                java.util.Map.entry("InvoiceVerificationReceipt", io.agentflow.expense.InvoiceVerificationService.Receipt.class),
                java.util.Map.entry("InvoiceVerificationOptions", io.agentflow.expense.InvoiceVerificationService.Options.class),
                java.util.Map.entry("InvoiceVerificationView", io.agentflow.expense.InvoiceVerificationService.View.class),
                java.util.Map.entry("InvoiceVerificationPage", io.agentflow.expense.InvoiceVerificationService.Page.class),
                java.util.Map.entry("InvoiceReceipt", io.agentflow.expense.InvoiceWalletService.Receipt.class),
                java.util.Map.entry("InvoiceOriginalMetadata", io.agentflow.expense.InvoiceWalletService.OriginalMetadata.class),
                java.util.Map.entry("InvoiceWalletItem", io.agentflow.expense.InvoiceWalletService.Item.class),
                java.util.Map.entry("InvoiceWalletPage", io.agentflow.expense.InvoiceWalletService.Page.class),
                java.util.Map.entry("InvoiceWalletOptions", io.agentflow.expense.InvoiceWalletService.Options.class),
                java.util.Map.entry("InvoiceFacts", io.agentflow.expense.Invoice.VerifiedFacts.class),
                java.util.Map.entry("InvoiceKey", io.agentflow.expense.InvoiceKey.class),
                java.util.Map.entry("ExpenseUse", io.agentflow.expense.ExpenseUse.class),
                java.util.Map.entry("FinanceCatalog", io.agentflow.finance.FinanceCatalog.class),
                java.util.Map.entry("FinanceLegalEntity", io.agentflow.finance.FinanceCatalog.LegalEntity.class),
                java.util.Map.entry("FinanceCategory", io.agentflow.finance.FinanceCatalog.Category.class),
                java.util.Map.entry("FinanceCostCenter", io.agentflow.finance.FinanceCatalog.CostCenter.class),
                java.util.Map.entry("FinanceProject", io.agentflow.finance.FinanceCatalog.Project.class),
                java.util.Map.entry("FinanceCity", io.agentflow.finance.FinanceCatalog.City.class),
                java.util.Map.entry("BusinessReference", io.agentflow.approval.model.BusinessReference.class),
                java.util.Map.entry("Money", io.agentflow.finance.Money.class),
                java.util.Map.entry("ExpenseContent", io.agentflow.expense.ExpenseContent.class),
                java.util.Map.entry("ExpenseLine", io.agentflow.expense.ExpenseLine.class),
                java.util.Map.entry("ExpenseResponse", io.agentflow.expense.ExpenseResponse.class),
                java.util.Map.entry("ExpenseFinancialRound", io.agentflow.expense.ExpenseResponse.FinancialRound.class),
                java.util.Map.entry("CopySnapshot", io.agentflow.approval.copy.CopyReadService.Snapshot.class),
                java.util.Map.entry("AttachmentMetadata", io.agentflow.attachment.AttachmentService.Metadata.class),
                java.util.Map.entry("AttachmentOptions", io.agentflow.attachment.AttachmentService.Options.class),
                java.util.Map.entry("WebhookTarget", io.agentflow.integration.WebhookTargets.TargetView.class),
                java.util.Map.entry("WebhookDelivery", io.agentflow.integration.JdbcWebhookStore.Summary.class),
                java.util.Map.entry("WebhookPage", io.agentflow.integration.WebhookController.Page.class),
                java.util.Map.entry("WebhookAttempt", io.agentflow.integration.JdbcWebhookStore.Attempt.class),
                java.util.Map.entry("WebhookRetryRequest", io.agentflow.integration.JdbcWebhookStore.RetryRequest.class),
                java.util.Map.entry("WebhookDetail", io.agentflow.integration.WebhookController.Detail.class),
                java.util.Map.entry("RoundDiagram", io.agentflow.approval.history.RoundDiagramPort.Diagram.class),
                java.util.Map.entry("RoundCandidateSnapshot", io.agentflow.approval.history.RoundDiagramPort.CandidateSnapshot.class),
                java.util.Map.entry("RoundDiagramNode", io.agentflow.approval.history.RoundDiagramPort.Node.class),
                java.util.Map.entry("RoundDiagramEdge", io.agentflow.approval.history.RoundDiagramPort.Edge.class),
                java.util.Map.entry("AuditSearchItem", io.agentflow.approval.operations.AuditSearchPort.Item.class),
                java.util.Map.entry("AuditSearchPage", io.agentflow.approval.operations.AuditSearchController.Page.class),
                java.util.Map.entry("ApplicationSearchItem", io.agentflow.approval.operations.ApplicationSearchPort.Item.class),
                java.util.Map.entry("ApplicationSearchPage", io.agentflow.approval.operations.ApplicationSearchController.Page.class),
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
                java.util.Map.entry("AssistReference", io.agentflow.agent.AssistInput.Reference.class),
                java.util.Map.entry("AssistSource", io.agentflow.agent.AssistModelPort.Source.class),
                java.util.Map.entry("AssistInputOptions", io.agentflow.agent.AssistExecutionService.InputOptions.class),
                java.util.Map.entry("AssistReceipt", io.agentflow.agent.AssistExecutionService.Receipt.class),
                java.util.Map.entry("AssistClaim", io.agentflow.agent.AssistSuggestion.Claim.class),
                java.util.Map.entry("AssistSuggestion", io.agentflow.agent.AssistSuggestion.class),
                java.util.Map.entry("AssistReview", io.agentflow.agent.AssistRun.Review.class),
                java.util.Map.entry("AssistRunItem", io.agentflow.agent.AssistRunReadPort.Item.class),
                java.util.Map.entry("AssistRunPage", io.agentflow.agent.AssistRunQueryService.Page.class),
                java.util.Map.entry("AssistRunDetail", io.agentflow.agent.AssistRunQueryService.Detail.class),
                java.util.Map.entry("Application", io.agentflow.approval.ApplicationResponse.class),
                java.util.Map.entry("SubmissionRound", io.agentflow.approval.SubmissionRoundResponse.class),
                java.util.Map.entry("InitiatorContext", io.agentflow.organization.InitiatorContext.class),
                java.util.Map.entry("Definition", io.agentflow.definition.DefinitionController.DefinitionResponse.class),
                java.util.Map.entry("DefinitionAvailabilityChange", io.agentflow.definition.DefinitionAvailabilityChange.class),
                java.util.Map.entry("DefinitionAvailabilityHistory", io.agentflow.definition.DefinitionAvailabilityController.HistoryPage.class),
                java.util.Map.entry("PublicationResult", io.agentflow.definition.DefinitionController.PublicationResponse.class),
                java.util.Map.entry("Publication", io.agentflow.definition.DefinitionPublication.class),
                java.util.Map.entry("ValidationResult", io.agentflow.definition.DefinitionController.ValidationResponse.class),
                java.util.Map.entry("BranchDiagnostic", io.agentflow.definition.BranchCoverageAnalyzer.Diagnostic.class),
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
                java.util.Map.entry("AuthOptions", io.agentflow.auth.AuthController.AuthOptions.class),
                java.util.Map.entry("LoginResponse", io.agentflow.auth.AuthController.LoginResponse.class),
                java.util.Map.entry("CurrentIdentity", io.agentflow.auth.AuthService.LoginResult.class),
                java.util.Map.entry("SimulationResult", io.agentflow.definition.DefinitionSimulator.Result.class),
                java.util.Map.entry("FieldPreviewResult", io.agentflow.form.FormFieldProjection.class),
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

    @Test
    void availabilityIsOptionalOnlyOnLegacyDefinitionWriteReplays() throws Exception {
        var schemas = document().path("components").path("schemas");
        assertThat(java.util.stream.StreamSupport.stream(schemas.path("Definition").path("required").spliterator(), false)
                .map(JsonNode::asText)).doesNotContain("startEnabled");
        assertThat(java.util.stream.StreamSupport.stream(schemas.path("DefinitionCatalogItem").path("required").spliterator(), false)
                .map(JsonNode::asText)).contains("startEnabled");
    }
}
