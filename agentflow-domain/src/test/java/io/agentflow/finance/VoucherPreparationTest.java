package io.agentflow.finance;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 准备租约只允许一个实际凭证身份，不把迟到、零金额或未配置误作过账成功。
 * @author owlzhangfq@gmail.com
 */
class VoucherPreparationTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    @Test void successfulPreparationUsesOriginalIdentityAndUnchangedInput() {
        var queued = VoucherPreparation.queue(input("a".repeat(64)), NOW); var started = queued.start(NOW.plusSeconds(1), NOW.plusSeconds(31));
        var ready = started.finish(VoucherPreparation.Result.ready(queued.input().id()), NOW.plusSeconds(2));
        assertThat(ready.version()).isEqualTo(3); assertThat(ready.status()).isEqualTo(VoucherPreparation.Status.READY);
        assertThat(ready.input()).isEqualTo(queued.input()); assertThat(ready.active()).isFalse();
        assertThatThrownBy(() -> ready.start(NOW.plusSeconds(3), NOW.plusSeconds(30))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> started.finish(VoucherPreparation.Result.ready(UUID.randomUUID()), NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
    }
    @Test void expiredCompletionNeverRetainsReadyVoucherIdentity() {
        var queued = VoucherPreparation.queue(input("a".repeat(64)), NOW); var started = queued.start(NOW, NOW.plusSeconds(30));
        var expired = started.finish(VoucherPreparation.Result.ready(queued.input().id()), NOW.plusSeconds(30));
        assertThat(expired.status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE); assertThat(expired.result().code()).isEqualTo("LEASE_EXPIRED");
        assertThat(expired.result().operationId()).isNull();
        assertThatThrownBy(() -> new VoucherPreparation(started.input(), 3, VoucherPreparation.Status.READY, NOW, NOW, NOW.plusSeconds(30), NOW.plusSeconds(30), VoucherPreparation.Result.ready(started.input().id()))).isInstanceOf(DomainException.class);
    }
    @Test void unconfiguredTargetCanBeVisibleButCannotCreateReadyResult() {
        var queued = VoucherPreparation.queue(input(null), NOW); var started = queued.start(NOW, NOW.plusSeconds(30));
        assertThat(started.finish(VoucherPreparation.Result.unavailable("NOT_CONFIGURED"), NOW).status()).isEqualTo(VoucherPreparation.Status.UNAVAILABLE);
        assertThatThrownBy(() -> started.finish(VoucherPreparation.Result.ready(queued.input().id()), NOW)).isInstanceOf(DomainException.class);
    }
    @Test void zeroAmountIsDistinctFromReadyAndOtherFailures() {
        var queued = VoucherPreparation.queue(input(null), NOW); var started = queued.start(NOW, NOW.plusSeconds(30));
        var zero = started.finish(VoucherPreparation.Result.notRequired(), NOW);
        assertThat(zero.status()).isEqualTo(VoucherPreparation.Status.NOT_REQUIRED); assertThat(zero.result().operationId()).isNull();
        assertThatThrownBy(() -> new VoucherPreparation.Result(VoucherPreparation.Status.NOT_REQUIRED, null, "OTHER")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherPreparation.Result(VoucherPreparation.Status.BLOCKED, UUID.randomUUID(), "ERROR")).isInstanceOf(DomainException.class);
    }
    @Test void sourceRejectsUnrelatedBusinessAndInvalidFinancialVersion() {
        var source = input(null).source();
        assertThat(source.kind()).isEqualTo(VoucherCommand.Kind.EMPLOYEE_ADVANCE);
        assertThatThrownBy(() -> new VoucherPreparation.Source(source.tenantId(), BusinessReference.Type.EXPENSE_PLAN, source.businessId(), source.applicationId(), 1, 5, 3, "alice")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherPreparation.Source(source.tenantId(), BusinessReference.Type.EXPENSE, source.businessId(), source.applicationId(), 1, 5, 0, "alice")).isInstanceOf(DomainException.class);
    }
    @Test void paymentSourceRequiresBothOriginalOperationAndPersistedRevision() {
        var source = input(null).source(); var id = UUID.randomUUID();
        var paid = new VoucherPreparation.Source(source.tenantId(), source.businessType(), source.businessId(), source.applicationId(), 1, 5, 3, "alice", id, 4L);
        assertThat(paid.kind()).isEqualTo(VoucherCommand.Kind.PAYMENT);
        assertThatThrownBy(() -> new VoucherPreparation.Source(source.tenantId(), source.businessType(), source.businessId(), source.applicationId(), 1, 5, 3, "alice", id, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new VoucherPreparation.Source(source.tenantId(), source.businessType(), source.businessId(), source.applicationId(), 1, 5, 3, "alice", null, 4L)).isInstanceOf(DomainException.class);
    }
    @Test void selectionSurvivesCompletionAndExpirationWithoutChangingOriginalInput() {
        var queued = VoucherPreparation.queue(input("a".repeat(64)), NOW);
        var request = new AccountMappingPort.Request(UUID.randomUUID(), "CNY", java.util.List.of(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "")));
        var started = queued.start(NOW, NOW.plusSeconds(30), request);
        assertThat(started.finish(VoucherPreparation.Result.ready(queued.input().id()), NOW.plusSeconds(1)).mappingRequest()).isEqualTo(request);
        var expired = started.finish(VoucherPreparation.Result.ready(queued.input().id()), NOW.plusSeconds(30));
        assertThat(expired.mappingRequest()).isEqualTo(request); assertThat(expired.input()).isEqualTo(queued.input());
        assertThat(expired.result().code()).isEqualTo("LEASE_EXPIRED");
    }
    @Test void queuedOrUnconfiguredPreparationCannotPretendToHaveSelectedAMapping() {
        var request = new AccountMappingPort.Request(UUID.randomUUID(), "CNY", java.util.List.of(new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "")));
        assertThatThrownBy(() -> new VoucherPreparation(input("a".repeat(64)), 1, VoucherPreparation.Status.QUEUED, NOW, null, null, null, null, request)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> VoucherPreparation.queue(input(null), NOW).start(NOW, NOW.plusSeconds(30), request)).isInstanceOf(DomainException.class);
    }
    @Test void managedSelectionMustRetainPreparationTenantAndTarget() {
        var queued = VoucherPreparation.queue(input("a".repeat(64)), NOW); var entity = UUID.randomUUID();
        var key = new AccountMappingPort.Key(AccountMappingPort.Role.EMPLOYEE_PAYABLE, "");
        for (var scope : java.util.List.of(java.util.List.of("foreign", "a".repeat(64)), java.util.List.of("demo", "b".repeat(64)))) {
            var selection = new AccountMappingSelection(UUID.randomUUID(), 1, 0, 1, "c".repeat(64), scope.get(1));
            var managed = new ManagedAccountMapping(scope.get(0), entity, "CNY", selection, java.util.List.of(new AccountMappingPort.Entry(key, "synthetic-payable")));
            var request = new AccountMappingPort.Request(entity, "CNY", java.util.List.of(key), managed);
            assertThatThrownBy(() -> queued.start(NOW, NOW.plusSeconds(30), request)).isInstanceOfSatisfying(DomainException.class,
                    failure -> assertThat(failure.code()).isEqualTo("INVALID_VOUCHER_PREPARATION"));
        }
    }
    private static VoucherPreparation.Input input(String target) {
        return new VoucherPreparation.Input(UUID.randomUUID(), new VoucherPreparation.Source("demo", BusinessReference.Type.ADVANCE_REQUEST, UUID.randomUUID(), UUID.randomUUID(), 1, 5, 3, "alice"), 1, "manager", target);
    }
}
