package io.agentflow.expense;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.VoucherPreparation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 核销账本的原输入、租户外键、并发与追加审计验证；合成存储夹具不代表实际审批结算。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.expenses.settlement-worker-enabled=false",
        "spring.datasource.url=${AGENTFLOW_SETTLEMENT_TEST_URL:jdbc:h2:mem:expense-settlement-store;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENTFLOW_SETTLEMENT_TEST_USER:sa}",
        "spring.datasource.password=${AGENTFLOW_SETTLEMENT_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=${AGENTFLOW_SETTLEMENT_TEST_DRIVER:org.h2.Driver}"})
class ExpenseSettlementPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    @Autowired JdbcExpenseSettlementRepository settlements;
    @Autowired ExpenseReportRepository reports;
    @Autowired ApplicationRepository applications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.agentflow.common.JsonUtil json;

    @Test void queueAndRetryRetainOriginalIdentityAndEveryRevision() {
        var initial = fixture(); create(initial); var id = initial.input().source().businessId();
        assertThat(settlements.find("demo", id)).contains(initial); assertThat(settlements.find("foreign", id)).isEmpty();
        assertThat(settlements.pending()).extracting(JdbcExpenseSettlementRepository.Candidate::reportId).contains(id);
        var blocked = initial.block("INVOICE_VERIFICATION_REQUIRED", NOW.plusSeconds(1)); update(blocked);
        var retry = blocked.retry(NOW.plusSeconds(2)); update(retry);
        var held = retry.requireReview("PAYMENT_REVERSED", NOW.plusSeconds(3)); update(held);
        assertThat(settlements.find("demo", id)).contains(held);
        assertThat(settlements.revision("demo", id, blocked.version())).contains(blocked);
        assertThat(settlements.revision("foreign", id, blocked.version())).isEmpty();
        assertThat(settlements.revision("demo", id, held.version() + 1)).isEmpty();
        assertThat(jdbc.queryForList("SELECT version FROM expense_settlement_revision WHERE tenant_id='demo' AND report_id=? ORDER BY version", Long.class, id.toString())).containsExactly(1L, 2L, 3L, 4L);
        assertThatThrownBy(() -> update(blocked)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> create(initial)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void historicalRevisionRejectsPayloadFromAnotherVersion() {
        var initial = fixture(); create(initial); var id = initial.input().source().businessId();
        var blocked = initial.block("INVOICE_VERIFICATION_REQUIRED", NOW.plusSeconds(1)); update(blocked);
        jdbc.update("UPDATE expense_settlement_revision SET state_json=? WHERE tenant_id='demo' AND report_id=? AND version=?", json.write(initial), id.toString(), blocked.version());
        try { assertThatThrownBy(() -> settlements.revision("demo", id, blocked.version())).isInstanceOf(IllegalStateException.class)
                .hasMessage("Persisted expense settlement revision identity is inconsistent"); }
        finally { jdbc.update("UPDATE expense_settlement_revision SET state_json=? WHERE tenant_id='demo' AND report_id=? AND version=?", json.write(blocked), id.toString(), blocked.version()); }
    }

    @Test void originalSourceAndCreatedTimeCannotBeChangedByAForgedNextVersion() {
        var initial = fixture(); create(initial); var source = initial.input().source(); var at = NOW.plusSeconds(1);
        var changedSource = new VoucherPreparation.Source("demo", source.businessType(), source.businessId(), source.applicationId(), source.roundNo(), 2, source.businessVersion(), "alice");
        var input = new ExpenseSettlement.Input(changedSource, Money.zero("CNY"), Money.zero("CNY"), null, null, null, NOW);
        var changed = new ExpenseSettlement(input, 2, ExpenseSettlement.Status.BLOCKED, false, null, "SOURCE_CHANGED", NOW, at);
        assertThatThrownBy(() -> update(changed)).isInstanceOf(DomainException.class);
        var changedTime = new ExpenseSettlement(initial.input(), 2, ExpenseSettlement.Status.BLOCKED, false, null, "SOURCE_CHANGED", at, at);
        assertThatThrownBy(() -> update(changedTime)).isInstanceOf(DomainException.class);
        assertThat(settlements.find("demo", source.businessId())).contains(initial);
        jdbc.update("UPDATE expense_settlement SET financial_version=2 WHERE tenant_id='demo' AND report_id=?", source.businessId().toString());
        assertThatThrownBy(() -> settlements.find("demo", source.businessId())).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingOrCrossTenantBusinessFailsForeignKeysAndRevisionFailureRollsBackState() {
        var initial = fixture(); var source = initial.input().source();
        var foreign = new VoucherPreparation.Source("foreign", source.businessType(), source.businessId(), source.applicationId(), 1, 1, 1, "alice");
        var invalid = ExpenseSettlement.queue(new ExpenseSettlement.Input(foreign, Money.zero("CNY"), Money.zero("CNY"), null, null, null, NOW), NOW);
        assertThatThrownBy(() -> create(invalid)).isInstanceOf(DataIntegrityViolationException.class);
        create(initial);
        jdbc.update("INSERT INTO expense_settlement_revision(tenant_id,report_id,version,state_json) VALUES('demo',?,2,'{}')", source.businessId().toString());
        assertThatThrownBy(() -> update(initial.block("SOURCE_CHANGED", NOW.plusSeconds(1)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(settlements.find("demo", source.businessId())).contains(initial);
    }

    private ExpenseSettlement fixture() {
        var id = UUID.randomUUID();
        var application = Application.draftBusiness(UUID.randomUUID(), "demo", "SYNTHETIC-SETTLEMENT-" + id, "fixture", 1, "alice", "结算存储夹具", Map.of(), null, null, null, new BusinessReference(BusinessReference.Type.EXPENSE, id));
        var report = ExpenseReport.draft(id, "demo", application.id(), "alice", new ExpenseContent(UUID.randomUUID(), ExpenseContent.Type.DAILY, "存储验证", List.of(), List.of()));
        tx().executeWithoutResult(status -> { applications.save(application); reports.create(report, "fixture"); });
        var source = new VoucherPreparation.Source("demo", BusinessReference.Type.EXPENSE, id, application.id(), 1, 1, 1, "alice");
        return ExpenseSettlement.queue(new ExpenseSettlement.Input(source, Money.zero("CNY"), Money.zero("CNY"), null, null, null, NOW), NOW);
    }
    private void create(ExpenseSettlement value) { tx().executeWithoutResult(status -> settlements.create(value)); }
    private void update(ExpenseSettlement value) { tx().executeWithoutResult(status -> settlements.update(value)); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
}
