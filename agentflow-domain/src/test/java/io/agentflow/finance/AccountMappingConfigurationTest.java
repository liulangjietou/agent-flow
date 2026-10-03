package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseCategoryCatalog;
import io.agentflow.expense.ExpenseLine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 配置测试覆盖不可变历史、原子发布所需版本、缺项阻断及实际凭证身份绑定。
 * @author owlzhangfq@gmail.com
 */
class AccountMappingConfigurationTest {
    private static final String TENANT = "tenant-a";
    private static final String TARGET = "a".repeat(64);
    private static final UUID ENTITY = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final AccountMappingPort.Key OFFICE = new AccountMappingPort.Key(AccountMappingPort.Role.EXPENSE, "OFFICE");
    private static final AccountMappingPort.Key PAYABLE = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");

    @Test void publicationRetainsItsOriginalDefinitionAfterRevisionAndRepublishing() {
        var draft = AccountMappingDraft.create(TENANT, "head-office", definition("6602"));
        var first = publish(draft);
        var changed = first.draft().revise(1, definition("6603"));
        var second = changed.publish(2, catalog(TENANT, true), TARGET, "finance-admin", NOW.plusSeconds(1), "明确调整费用科目");
        assertThat(first.version().definition()).isEqualTo(definition("6602"));
        assertThat(first.version().version()).isEqualTo(1);
        assertThat(second.version().version()).isEqualTo(2);
        assertThat(second.version().draftRevision()).isEqualTo(2);
        assertThat(second.version().mappingId()).isEqualTo(first.version().mappingId());
        assertThat(second.version().definition().digest()).isNotEqualTo(first.version().definition().digest());
        assertCode(() -> first.draft().revise(0, definition("6603")), "CONCURRENCY_CONFLICT");
        assertCode(() -> first.draft().revise(1, first.draft().definition()), "ACCOUNT_MAPPING_UNCHANGED");
        assertCode(() -> publish(first.draft()), "ACCOUNT_MAPPING_UNCHANGED");
    }

    @Test void businessKeyCannotMoveBetweenLegalEntitiesOrCurrencies() {
        var draft = AccountMappingDraft.create(TENANT, "head-office", definition("6602"));
        assertCode(() -> draft.revise(1, new AccountMappingDefinition("法人迁移", UUID.randomUUID(), "CNY", draft.definition().entries())), "ACCOUNT_MAPPING_SCOPE_IMMUTABLE");
        assertCode(() -> draft.revise(1, new AccountMappingDefinition("币种迁移", ENTITY, "USD", draft.definition().entries())), "ACCOUNT_MAPPING_SCOPE_IMMUTABLE");
        assertCode(() -> draft.publish(0, catalog(TENANT, true), TARGET, "finance-admin", NOW, "发布"), "CONCURRENCY_CONFLICT");
        assertCode(() -> draft.publish(1, catalog("another-tenant", true), TARGET, "finance-admin", NOW, "发布"), "ACCOUNT_MAPPING_SCOPE_MISMATCH");
    }

    @Test void emptyUnknownAndDisabledExpenseKeysCannotBePublished() {
        var draft = AccountMappingDraft.create(TENANT, "head-office", definition("6602"));
        assertCode(() -> draft.publish(1, catalog(TENANT, false), TARGET, "finance-admin", NOW, "发布"), "ACCOUNT_MAPPING_NOT_PUBLISHABLE");
        assertCode(() -> draft.publish(1, new ExpenseCategoryCatalog(TENANT, 0, List.of()), TARGET, "finance-admin", NOW, "发布"), "ACCOUNT_MAPPING_NOT_PUBLISHABLE");
        var empty = AccountMappingDraft.create(TENANT, "head-office", new AccountMappingDefinition("待填写", ENTITY, "CNY", List.of()));
        assertCode(() -> publish(empty), "ACCOUNT_MAPPING_NOT_PUBLISHABLE");
        var nonExpense = AccountMappingDraft.create(TENANT, "employee", new AccountMappingDefinition("员工往来", ENTITY, "CNY", List.of(entry(PAYABLE, "2241"))));
        assertThat(nonExpense.publish(1, new ExpenseCategoryCatalog(TENANT, 0, List.of()), TARGET, "finance-admin", NOW, "仅往来科目").version().categoryRevision()).isZero();
    }

    @Test void definitionsAreCanonicalAndCannotBeMutatedByCallers() {
        var mutable = new ArrayList<>(List.of(entry(OFFICE, "6602"), entry(PAYABLE, "2241")));
        var original = new AccountMappingDefinition("办公科目", ENTITY, "CNY", mutable);
        mutable.clear();
        var reversed = new AccountMappingDefinition("办公科目", ENTITY, "CNY", List.of(entry(PAYABLE, "2241"), entry(OFFICE, "6602")));
        assertThat(original).isEqualTo(reversed);
        assertThat(original.digest()).isEqualTo(reversed.digest()).hasSize(64);
        assertThatThrownBy(() -> original.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertCode(() -> new AccountMappingDefinition("重复键", ENTITY, "CNY", List.of(entry(OFFICE, "6602"), entry(OFFICE, "6603"))), "INVALID_ACCOUNT_MAPPING_DEFINITION");
        assertCode(() -> new AccountMappingDefinition("不明确代码", ENTITY, "CNY", List.of(entry(OFFICE, "6602 "))), "INVALID_ACCOUNT_MAPPING_DEFINITION");
    }

    @Test void everyRequestedKeyMustExistWithoutSendingUnrelatedMappings() {
        var publication = publish(AccountMappingDraft.create(TENANT, "head-office", definition("6602"))).version();
        var original = new AccountMappingPort.Request(ENTITY, "CNY", List.of(OFFICE));
        var request = publication.request(original, 3);
        assertThat(request.managedMapping().entries()).containsExactly(entry(OFFICE, "6602"));
        assertThat(request.managedMapping().selection().definitionDigest()).isEqualTo(publication.definition().digest());
        assertThat(request.managedMapping().selection().activeRevision()).isEqualTo(3);
        assertThat(request.managedMapping().selection().targetDigest()).isEqualTo(TARGET);
        assertCode(() -> publication.request(new AccountMappingPort.Request(ENTITY, "CNY", List.of(new AccountMappingPort.Key(AccountMappingPort.Role.BANK, "bank-x"))), 3), "ACCOUNT_MAPPING_UNAVAILABLE");
        assertCode(() -> publication.request(new AccountMappingPort.Request(UUID.randomUUID(), "CNY", List.of(OFFICE)), 3), "ACCOUNT_MAPPING_SCOPE_MISMATCH");
        assertCode(() -> publication.request(new AccountMappingPort.Request(ENTITY, "USD", List.of(OFFICE)), 3), "ACCOUNT_MAPPING_SCOPE_MISMATCH");
        assertCode(() -> publication.request(request, 4), "ACCOUNT_MAPPING_SCOPE_MISMATCH");
    }

    @Test void erpCannotChangePublishedAccountsOrEchoAnUnmanagedRequest() {
        var request = publish(AccountMappingDraft.create(TENANT, "head-office", definition("6602"))).version()
                .request(new AccountMappingPort.Request(ENTITY, "CNY", List.of(OFFICE)), 1);
        var valid = new AccountMappingPort.Mapping(request, "erp-v1", NOW, NOW.plusSeconds(60), request.managedMapping().entries());
        assertThat(valid.matches(request, NOW)).isTrue();
        var legacy = new AccountMappingPort.Request(ENTITY, "CNY", request.keys());
        assertThat(new AccountMappingPort.Mapping(legacy, "erp-v1", NOW, NOW.plusSeconds(60), valid.entries()).matches(request, NOW)).isFalse();
        assertCode(() -> new AccountMappingPort.Mapping(request, "erp-v1", NOW, NOW.plusSeconds(60), List.of(entry(OFFICE, "6603"))), "INVALID_ACCOUNT_MAPPING");
        assertCode(() -> new AccountMappingPort.Request(UUID.randomUUID(), "CNY", request.keys(), request.managedMapping()), "INVALID_ACCOUNT_MAPPING_SELECTION");
        assertCode(() -> new AccountMappingPort.Request(ENTITY, "CNY", List.of(PAYABLE), request.managedMapping()), "INVALID_ACCOUNT_MAPPING_SELECTION");
    }

    @Test void voucherDigestBindsMappingSelectionWhileLegacyVectorRemainsStable() {
        var original = VoucherCommandTest.advanceCommand();
        assertThat(original.digest()).isEqualTo("99de112e40fd24e5c91b5aaac01d92e0b306b1e874f97db515bb5828dbac8b80");
        var definition = new AccountMappingDefinition("员工往来", ENTITY, "CNY", original.mapping().entries());
        var version = publish(AccountMappingDraft.create(TENANT, "employee", definition)).version();
        var first = managedCommand(original, version.request(original.mapping().request(), 1));
        var reactivated = managedCommand(original, version.request(original.mapping().request(), 2));
        assertThat(first.digest()).isNotEqualTo(original.digest()).isNotEqualTo(reactivated.digest());
        var next = new PublishedAccountMapping(version.mappingId(), TENANT, version.key(), 2, 2, version.categoryRevision(), definition, TARGET, "finance-admin", NOW, "同科目新版本");
        assertThat(managedCommand(original, next.request(original.mapping().request(), 1)).digest()).isNotEqualTo(first.digest());
        assertThat(new VoucherOperation.Input(first, TARGET).command()).isEqualTo(first);
        assertThatThrownBy(() -> new VoucherOperation.Input(first, "b".repeat(64))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherCommand(first.id(), "another-tenant", first.kind(), first.binding(), first.legalEntityId(), first.employeeId(), first.accountingDate(),
                first.totals(), first.period(), first.mapping(), first.lines(), first.payment(), first.createdAt(), first.expiresAt())).isInstanceOf(DomainException.class);
    }

    @Test void malformedPublicationStateAndUnboundTargetAreRejected() {
        assertCode(() -> new AccountMappingDraft(UUID.randomUUID(), TENANT, "head-office", 1, definition("6602"), 1, 0), "INVALID_ACCOUNT_MAPPING_DRAFT");
        var draft = AccountMappingDraft.create(TENANT, "head-office", definition("6602"));
        assertCode(() -> draft.publish(1, catalog(TENANT, true), "not-a-target", "finance-admin", NOW, "发布"), "INVALID_ACCOUNT_MAPPING_PUBLICATION");
        assertCode(() -> draft.publish(1, catalog(TENANT, true), TARGET, "finance-admin", NOW, " "), "INVALID_ACCOUNT_MAPPING_PUBLICATION");
        assertCode(() -> new AccountMappingSelection(UUID.randomUUID(), 0, 1, 1, "a".repeat(64), TARGET), "INVALID_ACCOUNT_MAPPING_SELECTION");
    }

    private static VoucherCommand managedCommand(VoucherCommand source, AccountMappingPort.Request request) {
        var mapping = new AccountMappingPort.Mapping(request, source.mapping().sourceVersion(), source.mapping().observedAt(), source.mapping().validUntil(), source.mapping().entries());
        return new VoucherCommand(source.id(), source.tenantId(), source.kind(), source.binding(), source.legalEntityId(), source.employeeId(), source.accountingDate(), source.totals(),
                source.period(), mapping, source.lines(), source.payment(), source.createdAt(), source.expiresAt());
    }
    private static AccountMappingDraft.Publication publish(AccountMappingDraft draft) {
        return draft.publish(draft.revision(), catalog(TENANT, true), TARGET, "finance-admin", NOW, "明确发布科目映射");
    }
    private static AccountMappingDefinition definition(String expense) { return new AccountMappingDefinition("办公科目", ENTITY, "CNY", List.of(entry(OFFICE, expense), entry(PAYABLE, "2241"))); }
    private static AccountMappingPort.Entry entry(AccountMappingPort.Key key, String code) { return new AccountMappingPort.Entry(key, code); }
    private static ExpenseCategoryCatalog catalog(String tenant, boolean active) {
        return new ExpenseCategoryCatalog(tenant, 1, List.of(new ExpenseCategoryCatalog.Category("OFFICE", "办公用品", List.of(ExpenseLine.Unit.ITEM), active)));
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(DomainException.class).extracting(failure -> ((DomainException) failure).code()).isEqualTo(code);
    }
}
