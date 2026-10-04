package io.agentflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.calendar.BusinessCalendar;
import io.agentflow.calendar.BusinessCalendarRepository;
import io.agentflow.calendar.CalendarRules;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.expense.*;
import io.agentflow.finance.Money;
import io.agentflow.form.FormFieldProjection;
import io.agentflow.form.FormSchema;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证来源编排调用原有授权边界和真实字段投影契约；本测试不代替 Flowable、身份服务与持久化整体验收。
 * @author owlzhangfq@gmail.com
 */
class ExpenseRiskAccessTest {
    private static final Instant AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);
    private static final UUID LEGAL = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Money AMOUNT = new Money(new BigDecimal("10.00"), "CNY");
    private static final String TASK = "current-decision-task";
    private final Actor actor = new Actor("demo", "reviewer", Set.of("APPROVER"));
    private final ExpenseReportRepository reports = mock(ExpenseReportRepository.class);
    private final ApprovalApplicationFacade applications = mock(ApprovalApplicationFacade.class);
    private final ApplicationFieldViews fields = mock(ApplicationFieldViews.class);
    private final SubmissionRoundRepository rounds = mock(SubmissionRoundRepository.class);
    private final AssistInputService decisions = mock(AssistInputService.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final BusinessCalendarRepository calendars = mock(BusinessCalendarRepository.class);
    private final JsonUtil json = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ExpenseRiskSources sources = new ExpenseRiskSources(json);
    private final ExpenseRiskAccess access = new ExpenseRiskAccess(reports, applications, fields, rounds, decisions, invoices, calendars, sources, json);
    private final FormSchema original = schema("原轮次费用明细");

    @BeforeEach void emptyUnverifiedInvoices() { when(invoices.findAll(anyString(), anyCollection())).thenReturn(Map.of()); }

    @Test void readsOnlyExplicitFrozenLinesAndChecksEveryReportBeforeReadingAnyInvoice() {
        UUID invoiceId = UUID.randomUUID(); var primary = fixture("employee", LEGAL, List.of(line(1, invoiceId), line(2)));
        var comparison = fixture("employee", LEGAL, List.of(line(1)));
        var selection = selection(List.of(selected(primary, 1), selected(comparison, 1)), null);
        var catalog = access.available(actor, primary.report().id(), TASK, selection, AT);
        var input = select(catalog);
        verify(decisions).requireDecision(primary.application(), TASK, actor);
        verify(applications).getForActor(actor, comparison.application().id());
        verify(invoices).findAll("demo", List.of(invoiceId));
        assertThat(input.documents()).hasSize(2); assertThat(input.documents().get(0).lineNos()).containsExactly(1);
        assertThat(json.write(input.sources())).doesNotContain(invoiceId.toString(), primary.report().id().toString(), "第 2 行的私人说明");
        assertThat(input.concerns()).extracting(ExpenseRiskInput.Concern::kind).contains(ExpenseRiskInput.Kind.CROSS_DOCUMENT);
        var order = inOrder(applications, fields, invoices);
        order.verify(applications).getForActor(actor, primary.application().id()); order.verify(fields).attachmentViewForActor(actor, primary.application(), 1);
        order.verify(applications).getForActor(actor, comparison.application().id()); order.verify(fields).attachmentViewForActor(actor, comparison.application(), 1);
        order.verify(invoices).findAll("demo", List.of(invoiceId));
    }

    @Test void currentAdminRoleCannotReplaceActualDecisionPermission() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2))); var admin = new Actor("demo", "reviewer", Set.of("ADMIN"));
        doThrow(new DomainException("FORBIDDEN", "Current task is required")).when(decisions).requireDecision(primary.application(), TASK, admin);
        error("FORBIDDEN", () -> access.available(admin, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT));
        verifyNoInteractions(calendars); verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    @ParameterizedTest @ValueSource(strings = {"hidden-primary", "masked-comparison", "denied-comparison"})
    void cannotUseReadableMainReportToSendAnUnreadableComparison(String denial) {
        var primary = fixture("employee", LEGAL, List.of(line(1))); var comparison = fixture("employee", LEGAL, List.of(line(1)));
        if (denial.equals("hidden-primary")) {
            when(fields.attachmentViewForActor(any(), eq(primary.application()), eq(1))).thenReturn(new FormFieldProjection(new FormSchema(2, List.of()), Map.of(), true));
        } else if (denial.equals("masked-comparison")) {
            when(fields.attachmentViewForActor(any(), eq(comparison.application()), eq(1))).thenReturn(new FormFieldProjection(schema("脱敏占位"), Map.of(), true));
        } else {
            UUID comparisonApplicationId = comparison.application().id();
            when(applications.getForActor(any(), eq(comparisonApplicationId))).thenThrow(new DomainException("NOT_FOUND", "Application not found"));
        }
        error(denial.equals("denied-comparison") ? "NOT_FOUND" : "FORBIDDEN",
                () -> access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1), selected(comparison, 1)), null), AT));
        verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    @Test void fieldGrantUsesTheSelectedHistoricalSchemaAndNeverTheChangedCurrentDefinition() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2)));
        when(primary.application().formSchema()).thenReturn(schema("当前已修改标签"));
        assertThat(select(access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT)).documents()).hasSize(1);
        verify(primary.application(), never()).formSchema();
    }

    @Test void primaryMustBeTheCurrentRoundEvenWhenOldRoundFieldsAreReadable() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2))); when(primary.application().roundNo()).thenReturn(2);
        error("AGENT_INPUT_CHANGED", () -> access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT));
        verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    @ParameterizedTest @ValueSource(strings = {"different-employee", "different-legal"})
    void deidentifiedComparisonCannotCombineUnrelatedApplicantsOrLegalEntities(String difference) {
        var primary = fixture("employee", LEGAL, List.of(line(1)));
        var comparison = fixture(difference.equals("different-employee") ? "other-employee" : "employee",
                difference.equals("different-legal") ? UUID.randomUUID() : LEGAL, List.of(line(1)));
        error("INVALID_AGENT_INPUT", () -> access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1), selected(comparison, 1)), null), AT));
        verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    @Test void onlyCurrentVerifiedInvoicesWithMatchingOwnerAndBuyerCanContributeNumbers() {
        var valid = invoice("employee", LEGAL, "20260000000000000001", AT.plusSeconds(60));
        var expired = invoice("employee", LEGAL, "20260000000000000002", AT);
        var otherOwner = invoice("other-employee", LEGAL, "20260000000000000003", AT.plusSeconds(60));
        var otherBuyer = invoice("employee", UUID.randomUUID(), "20260000000000000004", AT.plusSeconds(60));
        var primary = fixture("employee", LEGAL, List.of(line(1, valid.id(), expired.id(), otherOwner.id(), otherBuyer.id()), line(2)));
        when(invoices.findAll(anyString(), anyCollection())).thenReturn(Map.of(valid.id(), valid, expired.id(), expired, otherOwner.id(), otherOwner, otherBuyer.id(), otherBuyer));
        var catalog = access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT);
        var input = select(catalog);
        assertThat(input.concerns()).extracting(ExpenseRiskInput.Concern::kind).doesNotContain(ExpenseRiskInput.Kind.CONSECUTIVE_INVOICES);
        var coverage = json.read(input.sources().stream().filter(source -> source.reference().sourceId().equals(ExpenseRiskInput.COVERAGE_SOURCE)).findFirst().orElseThrow().content(), com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(coverage.path("invoiceCoverage").path("selectedCount").asInt()).isEqualTo(4);
        assertThat(coverage.path("invoiceCoverage").path("verifiedReferences").asInt()).isEqualTo(1);
        assertThat(coverage.path("invoiceCoverage").path("complete").asBoolean()).isFalse();
    }

    @Test void recheckingTheSameInvoiceChangesLocalBindingEvenWhenTheVisibleObservationIsUnchanged() {
        var invoice = invoice("employee", LEGAL, "20260000000000000001", AT.plusSeconds(60));
        var primary = fixture("employee", LEGAL, List.of(line(1, invoice.id()), line(2)));
        when(invoices.findAll(anyString(), anyCollection())).thenReturn(Map.of(invoice.id(), invoice));
        var scope = selection(List.of(selected(primary, 1, 2)), null);
        var before = select(access.available(actor, primary.report().id(), TASK, scope, AT));
        var facts = invoice.facts(); invoice.verified(invoice.version(), new Invoice.VerifiedFacts(facts.key(), facts.legalEntityId(), facts.gross(), facts.tax(), facts.issueDate(),
                facts.originalDigest(), "second-verification", AT, AT.plusSeconds(120)));
        var after = select(access.available(actor, primary.report().id(), TASK, scope, AT));
        assertThat(after.sources()).isEqualTo(before.sources());
        assertThat(after.documents().get(0).snapshotDigest()).isNotEqualTo(before.documents().get(0).snapshotDigest());
        assertThat(after).isNotEqualTo(before);
    }

    @Test void calendarIsExplicitTenantScopedAndRevisionsInvalidateConsent() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2)));
        var rules = new CalendarRules("Asia/Shanghai", Map.of(DayOfWeek.MONDAY, List.of(new CalendarRules.Period("09:00", "18:00"))), List.of());
        var calendar = BusinessCalendar.create("demo", "WORK", "合成日历", rules, "calendar-owner", AT);
        when(calendars.find("demo", calendar.id())).thenReturn(Optional.of(calendar));
        var scope = selection(List.of(selected(primary, 1, 2)), calendar.id());
        var before = select(access.available(actor, primary.report().id(), TASK, scope, AT));
        when(calendars.find("demo", calendar.id())).thenReturn(Optional.of(calendar.revise("新版本", rules, 1, "calendar-owner", AT.plusSeconds(1))));
        var after = select(access.available(actor, primary.report().id(), TASK, scope, AT.plusSeconds(1)));
        assertThat(before.sources()).isEqualTo(after.sources()); assertThat(before.calendar().revision()).isEqualTo(1); assertThat(after.calendar().revision()).isEqualTo(2);
        assertThat(after).isNotEqualTo(before); verify(calendars, times(2)).find("demo", calendar.id());
    }

    @Test void historyKeepsItsVersionButRechecksCurrentReadGrantsForEveryOriginalDocument() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2)));
        var input = select(access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT));
        clearInvocations(decisions); when(primary.application().version()).thenReturn(99L);
        assertThatCode(() -> access.requireReadable(actor, input)).doesNotThrowAnyException(); verifyNoInteractions(decisions);
        when(fields.attachmentViewForActor(actor, primary.application(), 1)).thenReturn(new FormFieldProjection(new FormSchema(2, List.of()), Map.of(), true));
        error("FORBIDDEN", () -> access.requireReadable(actor, input));
    }

    @Test void foreignTenantOrNonexistentSelectedLineCannotTurnIntoBroadDiscovery() {
        var primary = fixture("employee", LEGAL, List.of(line(1), line(2)));
        var foreign = new Actor("other-tenant", actor.userId(), actor.roles());
        error("NOT_FOUND", () -> access.available(foreign, primary.report().id(), TASK, selection(List.of(selected(primary, 1, 2)), null), AT));
        error("INVALID_AGENT_INPUT", () -> access.available(actor, primary.report().id(), TASK, selection(List.of(selected(primary, 3)), null), AT));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskAccess.Selection(List.of(selected(primary, 1), selected(primary, 2)), null));
        error("INVALID_AGENT_INPUT", () -> new ExpenseRiskAccess.SelectedDocument(primary.report().id(), 1, List.of()));
        verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    @Test void historyIndexUsesOriginalSensitiveFieldPermissionWithoutDiscoveringInvoices() {
        var primary = fixture("employee", LEGAL, List.of(line(1)));
        access.requirePrimaryReadable(actor, primary.report().id(), 1);
        verifyNoInteractions(decisions, calendars); verify(invoices, never()).findAll(anyString(), anyCollection());
        when(fields.attachmentViewForActor(actor, primary.application(), 1)).thenReturn(new FormFieldProjection(new FormSchema(2, List.of()), Map.of(), true));
        error("FORBIDDEN", () -> access.requirePrimaryReadable(actor, primary.report().id(), 1));
    }

    @Test void reviewDecisionRequiresMatchingApplicationAndCurrentOriginalRound() {
        var primary = fixture("employee", LEGAL, List.of(line(1)));
        var document = new ExpenseRiskInput.Document(1, primary.report().id(), primary.application().id(), 4, 1, 3, "a".repeat(64), List.of(1));
        access.requireDecision(actor, document, TASK); verify(decisions).requireDecision(primary.application(), TASK, actor);
        when(primary.application().roundNo()).thenReturn(2);
        error("AGENT_INPUT_CHANGED", () -> access.requireDecision(actor, document, TASK));
        var wrong = new ExpenseRiskInput.Document(1, primary.report().id(), UUID.randomUUID(), 4, 1, 3, "a".repeat(64), List.of(1));
        error("NOT_FOUND", () -> access.requireDecision(actor, wrong, TASK));
        verify(invoices, never()).findAll(anyString(), anyCollection());
    }

    private Fixture fixture(String employee, UUID legal, List<ExpenseLine> lines) {
        var report = mock(ExpenseReport.class); var application = mock(Application.class); var submitted = mock(SubmissionRound.class);
        UUID reportId = UUID.randomUUID(), applicationId = UUID.randomUUID();
        when(report.id()).thenReturn(reportId); when(report.tenantId()).thenReturn("demo"); when(report.employeeId()).thenReturn(employee);
        when(report.applicationId()).thenReturn(applicationId); when(report.version()).thenReturn(3L);
        when(application.id()).thenReturn(applicationId); when(application.version()).thenReturn(4L); when(application.roundNo()).thenReturn(1);
        when(submitted.formSchema()).thenReturn(original);
        var content = new ExpenseContent(legal, ExpenseContent.Type.DAILY, "内部费用标题", lines, List.of());
        var round = new ExpenseRound(1, 2, employee, AT.minusSeconds(120), content, "CNY", null, List.of(), List.of(), List.of(), List.of());
        when(report.rounds()).thenReturn(List.of(round)); when(reports.find("demo", reportId)).thenReturn(Optional.of(report));
        when(applications.getForActor(any(), eq(applicationId))).thenReturn(application);
        when(rounds.findByRound("demo", applicationId, 1)).thenReturn(Optional.of(submitted));
        when(fields.attachmentViewForActor(any(), eq(application), eq(1))).thenReturn(new FormFieldProjection(original, Map.of(), false));
        return new Fixture(report, application);
    }
    private static ExpenseLine line(int number, UUID... invoices) { return new ExpenseLine(number, "TAXI", DAY, null, "CITY", BigDecimal.ONE, ExpenseLine.Unit.ITEM,
            AMOUNT, Money.zero("CNY"), List.of(invoices), null, List.of(new CostAllocation("COST", null, AMOUNT)), "第 " + number + " 行的私人说明", null); }
    private static Invoice invoice(String owner, UUID legal, String number, Instant validUntil) {
        var invoice = Invoice.uploaded(UUID.randomUUID(), "demo", owner, UUID.randomUUID(), "b".repeat(64));
        invoice.verified(1, new Invoice.VerifiedFacts(new InvoiceKey(InvoiceKey.Type.DIGITAL, null, number), legal, AMOUNT, Money.zero("CNY"),
                DAY, invoice.originalDigest(), "synthetic-check", AT.minusSeconds(60), validUntil)); return invoice;
    }
    private ExpenseRiskInput select(ExpenseRiskSources.Catalog catalog) { return sources.select(catalog, catalog.sources().stream().map(source -> source.reference().sourceId()).toList()); }
    private static ExpenseRiskAccess.Selection selection(List<ExpenseRiskAccess.SelectedDocument> documents, UUID calendar) { return new ExpenseRiskAccess.Selection(documents, calendar); }
    private static ExpenseRiskAccess.SelectedDocument selected(Fixture fixture, Integer... lines) { return new ExpenseRiskAccess.SelectedDocument(fixture.report().id(), 1, List.of(lines)); }
    private static FormSchema schema(String label) { return new FormSchema(2, List.of(new FormSchema.Field(ExpenseFormContract.DETAILS, label, FormSchema.FieldType.TEXT,
            true, null, null, null, null, null, null, null, true, Map.of()))); }
    private static void error(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code)); }

    /**
     * 授权端口使用显式桩，费用行、票据和字段契约使用真实领域对象。
     * @author owlzhangfq@gmail.com
     */
    private record Fixture(ExpenseReport report, Application application) { }
}
