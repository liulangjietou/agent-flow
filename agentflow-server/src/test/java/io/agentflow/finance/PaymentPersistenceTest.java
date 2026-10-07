package io.agentflow.finance;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.JdbcOrganizationRepository;
import io.agentflow.organization.OrganizationAppointment;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 同一真实数据库事务保存授权、出纳登记及原付款命令，覆盖重启、竞争与半次写入失败。
 * @author owlzhangfq@gmail.com
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentPersistenceTest {
    private static final String TENANT = "payment-persistence";
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(20);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JsonUtil json;
    private JdbcPaymentAuthorizationRepository authorizations;
    private JdbcPaymentOperationRepository operations;
    private JdbcVoucherOperationRepository vouchers;

    @BeforeAll void database() {
        var source = new DriverManagerDataSource(System.getenv().getOrDefault("AGENTFLOW_PAYMENT_TEST_URL", "jdbc:h2:mem:payment_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"),
                System.getenv().getOrDefault("AGENTFLOW_PAYMENT_TEST_USER", "sa"), System.getenv().getOrDefault("AGENTFLOW_PAYMENT_TEST_PASSWORD", ""));
        Flyway.configure().dataSource(source).load().migrate(); jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        json = new JsonUtil(JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
        authorizations = new JdbcPaymentAuthorizationRepository(jdbc, json); operations = new JdbcPaymentOperationRepository(jdbc, json, authorizations); vouchers = new JdbcVoucherOperationRepository(jdbc, json);
    }

    @Test void registeredCommandAndAccountEvidenceSurviveRepositoryRestartWithFullRevisions() {
        var voucher = voucher(); var authorization = issue(voucher); var queued = register(authorization, voucher);
        assertThat(authorizations.find("foreign", authorization.terms().id())).isEmpty(); assertThat(operations.find("foreign", authorization.terms().id())).isEmpty();
        assertThat(authorizations.active(TENANT, BusinessReference.Type.ADVANCE_REQUEST, authorization.terms().binding().businessId()).orElseThrow().status()).isEqualTo(PaymentAuthorization.Status.EXECUTION_REGISTERED);
        var checking = queued.claim(NOW, LEASE); tx.executeWithoutResult(status -> operations.update(checking));
        var sending = checking.readyToSend(directory(authorization, NOW), account(authorization, NOW), NOW); tx.executeWithoutResult(status -> operations.update(sending));
        var restarted = new JdbcPaymentOperationRepository(jdbc, json, new JdbcPaymentAuthorizationRepository(jdbc, json));
        assertThat(restarted.find(TENANT, authorization.terms().id())).contains(sending);
        var expired = sending.expire(NOW.plusSeconds(20)); tx.executeWithoutResult(status -> restarted.update(expired));
        var query = expired.claim(NOW.plusSeconds(20), LEASE); tx.executeWithoutResult(status -> restarted.update(query));
        assertThat(query.status()).isEqualTo(PaymentOperation.Status.QUERYING); assertThat(query.input()).isEqualTo(queued.input());
        assertThat(jdbc.queryForList("SELECT version FROM payment_operation_revision WHERE tenant_id=? AND operation_id=? ORDER BY version", Long.class, TENANT, authorization.terms().id().toString())).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(jdbc.queryForList("SELECT version FROM payment_authorization_revision WHERE tenant_id=? AND authorization_id=? ORDER BY version", Long.class, TENANT, authorization.terms().id().toString())).containsExactly(1L, 2L);
    }

    @Test void historicalMissingDateJsonStillMatchesOriginalDecisionForEveryUnexecutedTransition() {
        var legacyJson = new JsonUtil(JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build());
        var repository = new JdbcPaymentAuthorizationRepository(jdbc, legacyJson);
        for (String transition : List.of("EXECUTE", "VOID", "EXPIRE")) {
            var voucher = voucher(); var initial = authorization(voucher);
            tx.executeWithoutResult(status -> repository.create(initial));
            String decision = jdbc.queryForObject("SELECT decision_json FROM payment_authorization WHERE tenant_id=? AND id=?", String.class, TENANT, initial.terms().id().toString());
            assertThat(decision).doesNotContain("dueDate");
            var restored = new JdbcPaymentAuthorizationRepository(jdbc, legacyJson).find(TENANT, initial.terms().id()).orElseThrow();
            assertThat(restored.decision().dueDate()).isNull(); assertThat(legacyJson.write(restored.decision())).isEqualTo(decision);
            var changed = switch (transition) {
                case "EXECUTE" -> execute(restored, voucher);
                case "VOID" -> restored.voidBeforeExecution("finance", "旧授权继续作废", NOW);
                default -> restored.expire(restored.decision().expiresAt());
            };
            tx.executeWithoutResult(status -> repository.update(changed));
            assertThat(repository.find(TENANT, initial.terms().id())).contains(changed);
            assertThat(jdbc.queryForObject("SELECT decision_json FROM payment_authorization WHERE tenant_id=? AND id=?", String.class, TENANT, initial.terms().id().toString())).isEqualTo(decision);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_authorization_revision WHERE tenant_id=? AND authorization_id=?", Integer.class, TENANT, initial.terms().id().toString())).isEqualTo(2);
        }
    }

    @Test void storedDueDateCannotBeChangedAndDivergentDateProjectionFailsClosed() {
        var voucher = voucher(); var dueDate = LocalDate.of(2026, 10, 1); var initial = authorization(voucher, dueDate);
        tx.executeWithoutResult(status -> authorizations.create(initial));
        assertThat(new JdbcPaymentAuthorizationRepository(jdbc, json).find(TENANT, initial.terms().id())).contains(initial);
        var alteredDecision = new PaymentAuthorization.Decision("finance", NOW, initial.decision().expiresAt(), dueDate.plusDays(1));
        var altered = new PaymentAuthorization(initial.terms(), alteredDecision, 1, PaymentAuthorization.Status.AUTHORIZED, NOW, null, null)
                .voidBeforeExecution("finance", "不能改写原日期", NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> authorizations.update(altered))).isInstanceOf(DomainException.class);
        var queued = register(initial, voucher);
        assertThat(authorizations.find(TENANT, initial.terms().id()).orElseThrow().decision().dueDate()).isEqualTo(dueDate);
        assertThat(queued.input().command().authorization().expiresAt()).isEqualTo(initial.decision().expiresAt());
        jdbc.update("UPDATE payment_authorization SET due_date=? WHERE tenant_id=? AND id=?", dueDate.plusDays(1), TENANT, initial.terms().id().toString());
        assertThatThrownBy(() -> authorizations.find(TENANT, initial.terms().id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void earliestAndLatestCalendarDatesSurviveJdbcWithoutTimeZoneConversion() {
        for (var dueDate : List.of(PaymentAuthorization.MIN_DUE_DATE, PaymentAuthorization.MAX_DUE_DATE)) {
            var value = authorization(voucher(), dueDate); tx.executeWithoutResult(status -> authorizations.create(value));
            assertThat(authorizations.find(TENANT, value.terms().id()).orElseThrow().decision().dueDate()).isEqualTo(dueDate);
            assertThat(jdbc.queryForObject("SELECT due_date FROM payment_authorization WHERE tenant_id=? AND id=?", LocalDate.class, TENANT, value.terms().id().toString())).isEqualTo(dueDate);
        }
    }

    @Test void activeAuthorizationIsUniqueAndOnlyUnexecutedVoidReleasesTheBusiness() {
        var voucher = voucher(); var first = issue(voucher); var other = authorization(voucher);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> authorizations.create(other))).isInstanceOf(DataIntegrityViolationException.class);
        var voided = first.voidBeforeExecution("finance", "重新核实付款期限", NOW); tx.executeWithoutResult(status -> authorizations.update(voided));
        assertThat(authorizations.active(TENANT, BusinessReference.Type.ADVANCE_REQUEST, first.terms().binding().businessId())).isEmpty();
        tx.executeWithoutResult(status -> authorizations.create(other)); register(other, voucher);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> authorizations.create(authorization(voucher)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> authorizations.update(first.expire(first.decision().expiresAt())))).isInstanceOf(DomainException.class);
    }

    @Test void executionRegistrationAndQueueRollbackTogetherAndHistoryFailureCannotLeaveAdvancedState() {
        var voucher = voucher(); var initial = issue(voucher); var executed = execute(initial, voucher); var queued = PaymentOperation.queue(executed, NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            authorizations.update(executed); operations.create(queued); throw new IllegalStateException("Synthetic transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(authorizations.find(TENANT, initial.terms().id())).contains(initial); assertThat(operations.find(TENANT, initial.terms().id())).isEmpty();
        register(initial, voucher);
        jdbc.update("INSERT INTO payment_operation_revision(tenant_id,operation_id,version,state_json) VALUES(?,?,2,'{}')", TENANT, initial.terms().id().toString());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.update(queued.claim(NOW, LEASE)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(operations.find(TENANT, initial.terms().id())).contains(queued);
    }

    @Test void staleVersionCannotOverwriteClaimAndUnregisteredAuthorizationCannotCreateAnOperation() {
        var voucher = voucher(); var initial = issue(voucher); var executed = execute(initial, voucher); var queued = PaymentOperation.queue(executed, NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.create(queued))).isInstanceOf(DomainException.class);
        register(initial, voucher); var claim = queued.claim(NOW, LEASE); tx.executeWithoutResult(status -> operations.update(claim));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.update(queued.claim(NOW.plusSeconds(1), LEASE)))).isInstanceOf(DomainException.class);
        assertThat(operations.find(TENANT, initial.terms().id())).contains(claim);
        assertThat(operations.due(NOW.plusSeconds(19))).extracting(JdbcPaymentOperationRepository.Candidate::id).doesNotContain(initial.terms().id());
        assertThat(operations.due(NOW.plusSeconds(20))).extracting(JdbcPaymentOperationRepository.Candidate::id).contains(initial.terms().id());
    }

    @Test void changedAuthorizationTermsAndOperationTargetCannotBeWrittenAndRelationalTamperingFailsClosed() {
        var voucher = voucher(); var initial = issue(voucher); var terms = initial.terms();
        var changed = new PaymentAuthorization.Terms(terms.id(), terms.tenantId(), terms.purpose(), terms.binding(), new Money(new BigDecimal("999"), "CNY"), terms.payee(),
                terms.voucherOperationId(), terms.voucherCommandDigest(), terms.voucherRevision(), terms.voucherReference(), terms.targetDigest());
        var altered = new PaymentAuthorization(changed, initial.decision(), 1, PaymentAuthorization.Status.AUTHORIZED, NOW, null, null).voidBeforeExecution("finance", "篡改金额", NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> authorizations.update(altered))).isInstanceOf(DomainException.class);
        var queued = register(initial, voucher); var claim = queued.claim(NOW, LEASE);
        var redirected = new PaymentOperation(new PaymentOperation.Input(claim.input().command(), "b".repeat(64), claim.input().debitAccount()), claim.version(), claim.status(), claim.attempts(), claim.dispatches(), claim.createdAt(), claim.updatedAt(), claim.nextAttemptAt(), claim.leaseUntil(), claim.accountEvidence(), claim.observation(), claim.conflictingObservation(), claim.highestRevision(), claim.failure());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> operations.update(redirected))).isInstanceOf(DomainException.class);
        jdbc.update("UPDATE payment_operation SET command_digest=? WHERE tenant_id=? AND id=?", "c".repeat(64), TENANT, terms.id().toString());
        assertThatThrownBy(() -> operations.find(TENANT, terms.id())).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE payment_authorization SET business_version=business_version+1 WHERE tenant_id=? AND id=?", TENANT, terms.id().toString());
        assertThatThrownBy(() -> authorizations.find(TENANT, terms.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void cashierPaginationFiltersCurrentEntityBeforeLimitAndNeverTrustsTamperedScopeColumn() {
        var visible = issue(voucher()); var hidden = issue(voucher()); var entity = visible.terms().payee().legalEntityId();
        var organization = new JdbcOrganizationRepository(jdbc, json); String subject = "cashier-scope-" + UUID.randomUUID();
        var department = new OrganizationUnit(UUID.randomUUID(), OrganizationUnit.Kind.DEPARTMENT, "财务部", entity, null, true, 1);
        var position = new OrganizationUnit(UUID.randomUUID(), OrganizationUnit.Kind.POSITION, "出纳岗位", entity, null, true, 1);
        var person = new OrganizationPerson(UUID.randomUUID(), subject, "出纳", true, false, 1);
        var appointment = new OrganizationAppointment(UUID.randomUUID(), person.id(), department.id(), position.id(), true, 1);
        tx.executeWithoutResult(status -> {
            if (!organization.initialized(TENANT)) organization.initialize(TENANT, "admin", NOW);
            organization.save(TENANT, new OrganizationUnit(entity, OrganizationUnit.Kind.LEGAL_ENTITY, "付款法人", null, null, true, 1), 0);
            organization.save(TENANT, department, 0); organization.save(TENANT, position, 0); organization.save(TENANT, person, 0); organization.save(TENANT, appointment, 0);
        });
        assertThat(authorizations.cashierPage(TENANT, subject, JdbcPaymentAuthorizationRepository.CashierFilter.ALL, null, 1)).containsExactly(visible);
        assertThat(authorizations.cashierPage("foreign", subject, JdbcPaymentAuthorizationRepository.CashierFilter.ALL, null, 1)).isEmpty();
        assertThat(new PaymentPersonnel(jdbc).eligible(TENANT, subject, hidden.terms().payee().legalEntityId())).isFalse();
        tx.executeWithoutResult(status -> organization.save(TENANT, appointment.revise(false, 1), 1));
        assertThat(authorizations.cashierPage(TENANT, subject, JdbcPaymentAuthorizationRepository.CashierFilter.ALL, null, 1)).isEmpty();
        jdbc.update("UPDATE payment_authorization SET legal_entity_id=? WHERE tenant_id=? AND id=?", hidden.terms().payee().legalEntityId().toString(), TENANT, visible.terms().id().toString());
        assertThatThrownBy(() -> authorizations.find(TENANT, visible.terms().id())).isInstanceOf(IllegalStateException.class);
    }

    private PaymentAuthorization issue(VoucherOperation voucher) { var value = authorization(voucher); tx.executeWithoutResult(status -> authorizations.create(value)); return value; }
    private PaymentAuthorization authorization(VoucherOperation voucher) {
        return authorization(voucher, null);
    }
    private PaymentAuthorization authorization(VoucherOperation voucher, LocalDate dueDate) {
        return PaymentAuthorization.issue(UUID.randomUUID(), voucher, new EmployeeAccountSnapshot(voucher.input().command().legalEntityId(), "alice", "payee-1", "****1234", "a".repeat(64), "v1"), "finance", NOW, NOW.plusSeconds(3600), dueDate);
    }
    private PaymentOperation register(PaymentAuthorization authorization, VoucherOperation voucher) {
        var executed = execute(authorization, voucher); var queued = PaymentOperation.queue(executed, NOW);
        tx.executeWithoutResult(status -> { authorizations.update(executed); operations.create(queued); }); return queued;
    }
    private PaymentAuthorization execute(PaymentAuthorization value, VoucherOperation voucher) { return value.registerExecution("cashier", directory(value, NOW), "debit-1", account(value, NOW), voucher, NOW); }
    private PaymentAccountsPort.Directory directory(PaymentAuthorization value, Instant now) {
        return new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(value.terms().payee().legalEntityId(), "CNY", "cashier"), "v1", now, now.plusSeconds(60),
                List.of(new PaymentAccountsPort.DebitAccount("debit-1", "业务账户", "****5678", "CNY", "v1")));
    }
    private EmployeeAccountPort.Account account(PaymentAuthorization value, Instant now) { return new EmployeeAccountPort.Account(value.terms().payee(), now.plusSeconds(60)); }
    private VoucherOperation voucher() {
        UUID business = UUID.randomUUID(), application = UUID.randomUUID(), entity = UUID.randomUUID(); var created = NOW.minusSeconds(3); var date = LocalDate.of(2026, 9, 28); var amount = new Money(new BigDecimal("100"), "CNY");
        jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','付款持久化验证','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application.toString(), TENANT, business.toString(), business.toString());
        var lines = List.of(new VoucherCommand.Line(1, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), VoucherCommand.Side.DEBIT, amount, 0, null, null, business),
                new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), VoucherCommand.Side.CREDIT, amount, 0, null, null, null));
        var mappingRequest = new AccountMappingPort.Request(entity, "CNY", lines.stream().map(VoucherCommand.Line::account).toList());
        var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, "CNY", date), "2026-09", "v1", date.withDayOfMonth(1), date.withDayOfMonth(30), created, NOW.plusSeconds(60));
        var mapping = new AccountMappingPort.Mapping(mappingRequest, "v1", created, NOW.plusSeconds(60), mappingRequest.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
        var command = new VoucherCommand(UUID.randomUUID(), TENANT, VoucherCommand.Kind.EMPLOYEE_ADVANCE, new VoucherCommand.Binding(business, application, 1, 5, 3), entity, "alice", date,
                new VoucherCommand.Totals(amount, Money.zero("CNY"), Money.zero("CNY")), period, mapping, lines, null, created, NOW.plusSeconds(60));
        var queued = VoucherOperation.queue(new VoucherOperation.Input(command, "a".repeat(64)), created); var claimed = queued.claim(created, LEASE);
        var posted = claimed.complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, NOW,
                "posting-1", "voucher-1", period.periodReference(), date, amount, amount, NOW, null)), NOW);
        tx.executeWithoutResult(status -> { vouchers.create(queued); vouchers.update(claimed); vouchers.update(posted); }); return posted;
    }
}
