package io.agentflow.finance;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.organization.JdbcOrganizationRepository;
import io.agentflow.organization.OrganizationAppointment;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 真实数据库验证筛选、总数和游标共用权限范围，账户索引不替代原付款事实。
 * @author owlzhangfq@gmail.com
 */
class CashierPaymentFiltersTest {
    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
    private static final JsonUtil JSON = new JsonUtil(JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
    private final CurrentActor actors = new CurrentActor();

    @AfterEach void clearActor() { actors.clear(); }

    @Test void filteredTotalsRemainStableAcrossPagesAndRejectCursorsFromAnotherFilter() {
        var f = new Fixture(null); UUID first = f.entity(true), second = f.entity(true), hidden = f.entity(false);
        var older = f.payment(first, "CNY", "a", "debit-1", "v1");
        var newer = f.payment(first, "CNY", "a", "debit-1", "v2");
        var other = f.payment(second, "CNY", "a", "debit-1", "v1");
        var secret = f.payment(hidden, "CNY", "a", "debit-1", "v1");
        f.payment(first, "CNY", "a", null, "v1");
        var workspace = f.workspace(); String key = CashierPaymentAccountKey.of(newer);
        var page = f.read(() -> workspace.list(Map.of("limit", "1", "legalEntityId", first.toString(), "debitAccount", key)));
        assertThat(page.totalCount()).isEqualTo(2); assertThat(page.items()).extracting(item -> item.payment().id()).containsExactly(newer.terms().id());
        assertThat(page.nextBeforeId()).isEqualTo(newer.terms().id()); assertThat(page.items().get(0).debitAccount().maskedAccount()).isEqualTo("****5678");
        var next = f.read(() -> workspace.list(Map.of("limit", "1", "legalEntityId", first.toString(), "debitAccount", key, "beforeId", page.nextBeforeId().toString())));
        assertThat(next.totalCount()).isEqualTo(2); assertThat(next.items()).extracting(item -> item.payment().id()).containsExactly(older.terms().id()); assertThat(next.nextBeforeId()).isNull();
        assertCode(() -> workspace.list(Map.of("legalEntityId", second.toString(), "beforeId", newer.terms().id().toString())), "INVALID_PAYMENT_QUERY");
        assertCode(() -> workspace.list(Map.of("beforeId", secret.terms().id().toString())), "NOT_FOUND");
        var denied = workspace.list(Map.of("legalEntityId", hidden.toString())); assertThat(denied.totalCount()).isZero(); assertThat(denied.items()).isEmpty();
        assertThat(workspace.list(Map.of("debitAccount", CashierPaymentAccountKey.of(other))).totalCount()).isEqualTo(1);
    }

    @Test void accountOptionsDeduplicateVersionsButKeepTargetEntityAndCurrencySeparate() {
        var f = new Fixture(null); UUID entity = f.entity(true), other = f.entity(true);
        var old = f.payment(entity, "CNY", "a", "shared-reference", "v1");
        var latest = f.payment(entity, "CNY", "a", "shared-reference", "v2");
        var changedTarget = f.payment(entity, "CNY", "b", "shared-reference", "v1");
        var changedCurrency = f.payment(entity, "USD", "a", "shared-reference", "v1");
        var changedEntity = f.payment(other, "CNY", "a", "shared-reference", "v1");
        assertThat(CashierPaymentAccountKey.of(old)).isEqualTo(CashierPaymentAccountKey.of(latest));
        assertThat(Set.of(CashierPaymentAccountKey.of(latest), CashierPaymentAccountKey.of(changedTarget),
                CashierPaymentAccountKey.of(changedCurrency), CashierPaymentAccountKey.of(changedEntity))).hasSize(4);
        var workspace = f.workspace(); var first = workspace.filterOptions(Map.of("limit", "2"));
        assertThat(first.legalEntities()).extracting(PaymentPersonnel.LegalEntity::id).containsExactlyInAnyOrder(entity, other);
        assertThat(first.accounts()).hasSize(2); assertThat(first.nextAfterAccountKey()).isNotNull();
        var second = workspace.filterOptions(Map.of("limit", "2", "afterAccountKey", first.nextAfterAccountKey()));
        assertThat(second.accounts()).hasSize(2); assertThat(second.nextAfterAccountKey()).isNull();
        var all = java.util.stream.Stream.concat(first.accounts().stream(), second.accounts().stream()).toList();
        assertThat(all).extracting(CashierPaymentWorkspace.AccountOption::key).doesNotHaveDuplicates();
        assertThat(all.stream().filter(option -> option.key().equals(CashierPaymentAccountKey.of(latest))).findFirst().orElseThrow().displayName()).isEqualTo("合成账户 v2");
        String publicJson = JSON.write(first); assertThat(publicJson).doesNotContain("shared-reference", "targetDigest", "sourceVersion", "accountDigest");
        assertCode(() -> workspace.filterOptions(Map.of("legalEntityId", entity.toString(), "afterAccountKey", CashierPaymentAccountKey.of(changedEntity))), "INVALID_PAYMENT_QUERY");
    }

    @Test void anUncheckedSelectionIsNotReportedAsAnActualDebitAccount() {
        var f = new Fixture(null); UUID entity = f.entity(true);
        var fresh = f.payment(entity, "CNY", "a", null, "v1"); var pending = f.payment(entity, "CNY", "a", null, "v1");
        var registered = f.payment(entity, "CNY", "a", "verified-reference", "v1");
        var request = PaymentExecutionRequest.queue(UUID.randomUUID(), pending, "cashier", "unverified-reference", "v1", pending.updatedAt());
        f.tx.executeWithoutResult(ignored -> f.requests.create(request));
        var workspace = f.workspace(); var page = workspace.list(Map.of("debitAccount", "UNASSIGNED"));
        assertThat(page.totalCount()).isEqualTo(2); assertThat(page.items()).extracting(item -> item.payment().id()).containsExactlyInAnyOrder(fresh.terms().id(), pending.terms().id());
        assertThat(page.items()).allSatisfy(item -> assertThat(item.debitAccount()).isNull());
        assertThat(workspace.filterOptions(Map.of()).accounts()).extracting(CashierPaymentWorkspace.AccountOption::key).containsExactly(CashierPaymentAccountKey.of(registered));
    }

    @Test void revocationAndTenantIsolationApplyToCountsAndOptionPagesToo() {
        var f = new Fixture(null); UUID entity = f.entity(true); var payment = f.payment(entity, "CNY", "a", "debit", "v1"); var workspace = f.workspace();
        assertThat(workspace.list(Map.of()).totalCount()).isEqualTo(1);
        actors.set(new Actor("foreign", "cashier", Set.of("CASHIER")));
        assertThat(workspace.list(Map.of()).totalCount()).isZero(); assertThat(workspace.filterOptions(Map.of()).accounts()).isEmpty();
        actors.set(new Actor(f.tenant, "cashier", Set.of("CASHIER")));
        f.jdbc.update("UPDATE organization_person SET active=FALSE WHERE tenant_id=? AND subject='cashier'", f.tenant);
        assertThat(workspace.list(Map.of()).items()).isEmpty(); assertThat(workspace.filterOptions(Map.of()).legalEntities()).isEmpty();
        assertCode(() -> workspace.list(Map.of("beforeId", payment.terms().id().toString())), "NOT_FOUND");
        actors.set(new Actor(f.tenant, "cashier", Set.of("ADMIN")));
        assertCode(() -> workspace.list(Map.of()), "FORBIDDEN"); assertCode(() -> workspace.filterOptions(Map.of()), "FORBIDDEN");
    }

    @Test void malformedFiltersAndForeignOptionCursorsFailAtTheReadBoundary() {
        var f = new Fixture(null); var workspace = f.workspace();
        for (var query : List.of(Map.of("limit", "0"), Map.of("limit", "101"), Map.of("limit", "01"), Map.of("legalEntityId", "1-1-1-1-1"),
                Map.of("legalEntityId", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"), Map.of("debitAccount", ""), Map.of("debitAccount", "a' OR 1=1"),
                Map.of("debitAccount", "A".repeat(64)), Map.of("sort", "id"), Map.of("beforeId", ""))) assertCode(() -> workspace.list(query), "INVALID_PAYMENT_QUERY");
        assertCode(() -> workspace.filterOptions(Map.of("afterAccountKey", "a".repeat(64))), "INVALID_PAYMENT_QUERY");
        assertCode(() -> workspace.filterOptions(Map.of("debitAccount", "UNASSIGNED")), "INVALID_PAYMENT_QUERY");
    }

    @Test void tamperedIndexCannotReplacePersistedCommandOrBeRepairedByAnUnrelatedTransition() {
        var f = new Fixture(null); UUID entity = f.entity(true);
        var registered = f.payment(entity, "CNY", "a", "debit", "v1");
        f.jdbc.update("UPDATE payment_authorization SET debit_account_key=? WHERE tenant_id=? AND id=?", "f".repeat(64), f.tenant, registered.terms().id().toString());
        assertThatThrownBy(() -> f.authorizations.find(f.tenant, registered.terms().id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.workspace().list(Map.of("debitAccount", "f".repeat(64)))).isInstanceOf(IllegalStateException.class);
        var fresh = f.payment(entity, "CNY", "a", null, "v1");
        f.jdbc.update("UPDATE payment_authorization SET debit_account_key=? WHERE tenant_id=? AND id=?", "f".repeat(64), f.tenant, fresh.terms().id().toString());
        assertCode(() -> f.tx.executeWithoutResult(ignored -> f.authorizations.update(fresh.voidBeforeExecution("finance", "取消原授权", fresh.updatedAt()))), "CONCURRENCY_CONFLICT");
    }

    @Test void nonemptyV113UpgradeAddsOnlyDerivedAccountIndexAndPreservesOriginalSnapshots() {
        var f = new Fixture("113"); UUID entity = f.entity(true);
        var selected = f.payment(entity, "CNY", "a", "legacy-reference", "v1");
        var fresh = f.payment(entity, "CNY", "a", null, "v1");
        var corrupt = f.payment(entity, "CNY", "a", "broken-reference", "v1");
        f.jdbc.update("UPDATE payment_authorization SET state_json=? WHERE tenant_id=? AND id=?", "{broken", f.tenant, corrupt.terms().id().toString());
        String columns = "tenant_id,id,terms_json,decision_json,state_json,status,version,authorized_at,expires_at,legal_entity_id";
        var before = f.jdbc.queryForList("SELECT " + columns + " FROM payment_authorization ORDER BY id");
        var revisions = f.jdbc.queryForList("SELECT * FROM payment_authorization_revision ORDER BY authorization_id,version");
        var flyway = Flyway.configure().dataSource(f.source).target("114").load(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(f.jdbc.queryForList("SELECT " + columns + " FROM payment_authorization ORDER BY id")).isEqualTo(before);
        assertThat(f.jdbc.queryForList("SELECT * FROM payment_authorization_revision ORDER BY authorization_id,version")).isEqualTo(revisions);
        assertThat(f.jdbc.queryForObject("SELECT debit_account_key FROM payment_authorization WHERE tenant_id=? AND id=?", String.class, f.tenant, selected.terms().id().toString())).isEqualTo(CashierPaymentAccountKey.of(selected));
        assertThat(f.jdbc.queryForObject("SELECT debit_account_key FROM payment_authorization WHERE tenant_id=? AND id=?", String.class, f.tenant, corrupt.terms().id().toString())).isNull();
        assertThat(flyway.migrate().migrationsExecuted).isZero(); assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        // V114 的旧列断言完成后再升到当前结构，新仓储不运行在旧表结构上。
        Flyway.configure().dataSource(f.source).load().migrate();
        assertThat(f.authorizations.find(f.tenant, selected.terms().id())).contains(selected); assertThat(f.authorizations.find(f.tenant, fresh.terms().id())).contains(fresh);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }

    /**
     * 每个用例独立建立真实挂账、原授权和当前法人任职；旧库按旧列保存相同领域事实。
     * @author owlzhangfq@gmail.com
     */
    private final class Fixture {
        private final String tenant = "cashier-filter-" + UUID.randomUUID();
        private final DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        private final JdbcTemplate jdbc = new JdbcTemplate(source);
        private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        private final JdbcPaymentAuthorizationRepository authorizations = new JdbcPaymentAuthorizationRepository(jdbc, JSON);
        private final JdbcPaymentOperationRepository operations = new JdbcPaymentOperationRepository(jdbc, JSON, authorizations);
        private final JdbcVoucherOperationRepository vouchers = new JdbcVoucherOperationRepository(jdbc, JSON);
        private final JdbcPaymentExecutionRequestRepository requests = new JdbcPaymentExecutionRequestRepository(jdbc, JSON, authorizations);
        private final JdbcOrganizationRepository organization = new JdbcOrganizationRepository(jdbc, JSON);
        private final UUID person = UUID.randomUUID();
        private final boolean legacy;
        private int sequence;

        private Fixture(String target) {
            var config = Flyway.configure().dataSource(source); if (target != null) config.target(target); config.load().migrate(); legacy = "113".equals(target);
            tx.executeWithoutResult(ignored -> { organization.initialize(tenant, "admin", NOW); organization.save(tenant, new OrganizationPerson(person, "cashier", "验收出纳", true, false, 1), 0); });
        }
        private UUID entity(boolean eligible) {
            UUID legal = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
            tx.executeWithoutResult(ignored -> {
                organization.save(tenant, new OrganizationUnit(legal, OrganizationUnit.Kind.LEGAL_ENTITY, "合成法人 " + legal, null, null, true, 1), 0);
                organization.save(tenant, new OrganizationUnit(department, OrganizationUnit.Kind.DEPARTMENT, "财务部", legal, null, true, 1), 0);
                organization.save(tenant, new OrganizationUnit(position, OrganizationUnit.Kind.POSITION, "出纳岗", legal, null, true, 1), 0);
                if (eligible) organization.save(tenant, new OrganizationAppointment(UUID.randomUUID(), person, department, position, true, 1), 0);
            }); return legal;
        }
        private CashierPaymentWorkspace workspace() {
            actors.set(new Actor(tenant, "cashier", Set.of("CASHIER"))); var personnel = new PaymentPersonnel(jdbc);
            var access = new PaymentAccess(actors, mock(VoucherAccess.class), authorizations, vouchers, personnel);
            return new CashierPaymentWorkspace(actors, access, mock(ApprovedPaymentSources.class), authorizations, requests, operations, mock(PaymentAccountsPort.class), personnel);
        }
        private <T> T read(java.util.function.Supplier<T> action) { return tx.execute(ignored -> action.get()); }
        private PaymentAuthorization payment(UUID entity, String currency, String target, String reference, String accountVersion) {
            UUID business = UUID.randomUUID(), application = UUID.randomUUID(); Instant authorized = NOW.plusSeconds(++sequence), created = NOW.minusSeconds(3);
            LocalDate date = LocalDate.of(2026, 10, 4); var amount = new Money(new BigDecimal("100"), currency);
            jdbc.update("INSERT INTO approval_application(id,tenant_id,business_no,process_key,definition_version,created_by,title,payload_json,status,round_no,version,business_type,business_id) VALUES(?,?,?,'fixture',1,'alice','出纳筛选验收','{}','APPROVED',1,5,'ADVANCE_REQUEST',?)", application.toString(), tenant, business.toString(), business.toString());
            var lines = List.of(new VoucherCommand.Line(1, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_RECEIVABLE, ""), VoucherCommand.Side.DEBIT, amount, 0, null, null, business),
                    new VoucherCommand.Line(2, new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, ""), VoucherCommand.Side.CREDIT, amount, 0, null, null, null));
            var request = new AccountMappingPort.Request(entity, currency, lines.stream().map(VoucherCommand.Line::account).toList());
            var period = new AccountingPeriodPort.OpenPeriod(new AccountingPeriodPort.Request(entity, currency, date), "2026-10", "v1", date.withDayOfMonth(1), date.withDayOfMonth(31), created, NOW.plusSeconds(120));
            var mapping = new AccountMappingPort.Mapping(request, "v1", created, NOW.plusSeconds(120), request.keys().stream().map(key -> new AccountMappingPort.Entry(key, "synthetic-" + key.role())).toList());
            var command = new VoucherCommand(UUID.randomUUID(), tenant, VoucherCommand.Kind.EMPLOYEE_ADVANCE, new VoucherCommand.Binding(business, application, 1, 5, 3), entity, "alice", date,
                    new VoucherCommand.Totals(amount, Money.zero(currency), Money.zero(currency)), period, mapping, lines, null, created, NOW.plusSeconds(120));
            var queued = VoucherOperation.queue(new VoucherOperation.Input(command, target.repeat(64)), created); var claimed = queued.claim(created, Duration.ofSeconds(20));
            var posted = claimed.complete(new FinanceResult.Success<>(new VoucherObservation(command.id(), command.digest(), VoucherObservation.Status.POSTED, 1L, NOW,
                    "posting-1", "voucher-1", period.periodReference(), date, amount, amount, NOW, null)), NOW);
            var initial = PaymentAuthorization.issue(UUID.randomUUID(), posted, new EmployeeAccountSnapshot(entity, "alice", "payee-1", "****1234", "c".repeat(64), "v1"), "finance", authorized, authorized.plusSeconds(3600));
            tx.executeWithoutResult(ignored -> { vouchers.create(queued); vouchers.update(claimed); vouchers.update(posted); createAuthorization(initial); });
            if (reference == null) return initial;
            var directory = new PaymentAccountsPort.Directory(new PaymentAccountsPort.Request(entity, currency, "cashier"), accountVersion, authorized, authorized.plusSeconds(60),
                    List.of(new PaymentAccountsPort.DebitAccount(reference, "合成账户 " + accountVersion, "****5678", currency, accountVersion)));
            var executed = initial.registerExecution("cashier", directory, reference, new EmployeeAccountPort.Account(initial.terms().payee(), authorized.plusSeconds(60)), posted, authorized);
            tx.executeWithoutResult(ignored -> {
                if (!legacy) { authorizations.update(executed); operations.create(PaymentOperation.queue(executed, authorized)); }
                else {
                    jdbc.update("UPDATE payment_authorization SET state_json=?,version=2,status='EXECUTION_REGISTERED',updated_at=? WHERE tenant_id=? AND id=?", JSON.write(executed), Timestamp.from(authorized), tenant, initial.terms().id().toString());
                    jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES(?,?,2,?)", tenant, initial.terms().id().toString(), JSON.write(executed));
                }
            }); return executed;
        }
        private void createAuthorization(PaymentAuthorization value) {
            if (!legacy) { authorizations.create(value); return; }
            var terms = value.terms(); var binding = terms.binding(); var decision = value.decision();
            jdbc.update("""
                    INSERT INTO payment_authorization(tenant_id,id,business_type,business_id,application_id,round_no,application_version,business_version,
                    purpose,voucher_operation_id,voucher_kind,terms_json,decision_json,state_json,version,status,active_business_id,authorized_at,expires_at,updated_at,legal_entity_id)
                    VALUES(?,?,'ADVANCE_REQUEST',?,?,1,5,3,'EMPLOYEE_ADVANCE',?,'EMPLOYEE_ADVANCE',?,?,?,1,'AUTHORIZED',?,?,?,?,?)
                    """, tenant, terms.id().toString(), binding.businessId().toString(), binding.applicationId().toString(), terms.voucherOperationId().toString(),
                    JSON.write(terms), JSON.write(decision), JSON.write(value), binding.businessId().toString(), Timestamp.from(decision.authorizedAt()),
                    Timestamp.from(decision.expiresAt()), Timestamp.from(value.updatedAt()), terms.payee().legalEntityId().toString());
            jdbc.update("INSERT INTO payment_authorization_revision(tenant_id,authorization_id,version,state_json) VALUES(?,?,1,?)", tenant, terms.id().toString(), JSON.write(value));
        }
    }
}
