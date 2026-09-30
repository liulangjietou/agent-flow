package io.agentflow.budget;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.CurrentActor;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceMasterDataPort;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.OrganizationInitiatorDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固定亚毫秒边界，外部已返回的台账不能因本地时间截断变成未来证据。
 * @author owlzhangfq@gmail.com
 */
class BudgetAdjustmentCheckPrecisionTest {
    private final Instant observed = Instant.parse("2026-09-30T05:00:00.123456789Z");
    private final Instant checked = Instant.parse("2026-09-30T05:00:00.123456900Z");
    private final Instant persisted = Instant.parse("2026-09-30T05:00:00.123457Z");
    private final UUID entity = UUID.randomUUID();
    private final BudgetAdjustmentContent content = new BudgetAdjustmentContent(entity, "纳秒时间验收", "保留真实台账时序", BudgetAdjustmentContent.Type.INCREASE,
            LocalDate.of(2026, 9, 30), null, "budget-target", money("0.01"));
    private final BudgetAdjustmentRequest request = BudgetAdjustmentRequest.draft(UUID.randomUUID(), "demo", UUID.randomUUID(), "alice", content);
    private final InitiatorContext initiator = new InitiatorContext(UUID.randomUUID(), UUID.randomUUID(), "alice", 1, entity, "法人", UUID.randomUUID(), "部门", UUID.randomUUID(), "岗位");
    private final FinanceCatalog catalog = new FinanceCatalog("alice", "v1", checked.plusSeconds(600), List.of(new FinanceCatalog.LegalEntity(entity, "法人", "CNY", false, "v1", "Asia/Shanghai")), List.of(), List.of(), List.of(), List.of());
    private final BudgetLedgerPort.Snapshot ledger = new BudgetLedgerPort.Snapshot(content.ledgerRequest("alice"), "v1", observed, checked.plusSeconds(300),
            List.of(new BudgetLedgerPort.Position(entity, "budget-target", "原预算", "v1", "2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), BudgetLedgerPort.PeriodStatus.OPEN, money("100"), money("20"), money("30"))));
    private final FinanceGatewayConfiguration configuration = configuration();
    private final BudgetAdjustmentCheck job = BudgetAdjustmentCheck.queue(new BudgetAdjustmentCheck.Input(UUID.randomUUID(), "demo", request.id(), request.applicationId(), "alice", 1, 1, 1, 1,
            initiator, configuration.destination("demo").orElseThrow().digest("demo"), content), checked.minusSeconds(2)).start(checked.minusSeconds(1), checked.plusSeconds(90));

    @Test void receivedNanosecondLedgerRemainsUsableWhenPreviewIsWithinSameMicrosecond() {
        var repository = mock(BudgetAdjustmentRepository.class); var catalogs = mock(FinanceMasterDataPort.class); var ledgers = mock(BudgetLedgerPort.class);
        when(repository.find("demo", request.id())).thenReturn(Optional.of(request));
        when(catalogs.catalog("demo", "alice")).thenReturn(new FinanceResult.Success<>(catalog));
        when(ledgers.read("demo", job.input().targetDigest(), ledger.request())).thenReturn(new FinanceResult.Success<>(ledger));
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(checked);
            var result = new BudgetAdjustmentCheckEvaluator(repository, catalogs, ledgers, configuration).evaluate(job);
            assertThat(result.status()).as(String.valueOf(result.code())).isEqualTo(BudgetAdjustmentCheck.Status.READY);
            assertThat(result.evidence().preview().submittedAt()).isEqualTo(persisted);
            assertThat(result.evidence().preview().ledger().observedAt()).isEqualTo(observed);
        }
    }

    @Test void completionCannotBeRoundedBeforeTheEvidenceItPersists() {
        request.freeze(1, 1, catalog, job.input().targetDigest(), ledger, initiator, persisted);
        var result = BudgetAdjustmentCheck.Result.ready(new BudgetAdjustmentCheck.Evidence(catalog, request.currentRound(), observed.plusSeconds(300)));
        var repository = mock(BudgetAdjustmentRepository.class); var applications = mock(ApplicationRepository.class);
        var directory = mock(OrganizationInitiatorDirectory.class); var jobs = mock(JdbcBudgetAdjustmentCheckRepository.class);
        var original = BudgetAdjustmentRequest.draft(request.id(), "demo", request.applicationId(), "alice", content);
        var application = mock(Application.class);
        when(repository.find("demo", request.id())).thenReturn(Optional.of(original));
        when(applications.findById("demo", request.applicationId())).thenReturn(Optional.of(application));
        when(application.editable()).thenReturn(true); when(application.version()).thenReturn(1L); when(application.nextSubmissionRound()).thenReturn(1);
        when(directory.findCurrent(any(), eq(initiator.appointmentId()))).thenReturn(Optional.of(initiator));
        when(jobs.find("demo", job.input().id())).thenReturn(Optional.of(job));
        var service = new BudgetAdjustmentCheckService(mock(CurrentActor.class), repository, mock(ApprovalApplicationFacade.class), applications, directory, configuration, jobs, 300);
        service.finish(job, result, persisted.plusNanos(1));
        var stored = ArgumentCaptor.forClass(BudgetAdjustmentCheck.class); verify(jobs).update(stored.capture());
        assertThat(stored.getValue().status()).isEqualTo(BudgetAdjustmentCheck.Status.READY);
        assertThat(stored.getValue().completedAt()).isEqualTo(persisted.plusNanos(1000));
    }

    private static Money money(String value) { return new Money(new BigDecimal(value), "CNY"); }
    private static FinanceGatewayConfiguration configuration() {
        var config = new FinanceGatewayConfiguration(); config.setEnabled(true); var target = new FinanceGatewayConfiguration.Target();
        target.setEndpoint("https://budget-precision.example/finance"); target.setToken("synthetic-token"); config.getTenants().put("demo", target); return config;
    }
}
