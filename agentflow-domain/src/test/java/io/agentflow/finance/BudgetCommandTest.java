package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 预算命令使用可跨语言复算的固定摘要，版本和结果形状不能相互混淆。
 * @author owlzhangfq@gmail.com
 */
class BudgetCommandTest {
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REPORT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ENTITY = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Test
    void digestMatchesIndependentUtf8LengthPrefixedVectorAndNormalizesEquivalentMoney() {
        var command = command("100", null);
        // 此值由 Python hashlib 与 struct.pack('>i', 字节长度) 独立计算，包含中文和空项目。
        assertThat(command.digest()).isEqualTo("240aecd51a8aaa95bc378695ec620ceea69c4b7e8a7def36bd8d4b4a738fd82a");
        assertThat(command("100.000", null).digest()).isEqualTo(command.digest());
        assertThat(command("100.01", null).digest()).isNotEqualTo(command.digest());
        assertThat(command("100", "null").digest()).isNotEqualTo(command.digest());
        assertThat(new BudgetCommand(ID, "tenant-b", command.action(), command.position(), null).digest()).isNotEqualTo(command.digest());
        assertThat(new BudgetCommand(UUID.randomUUID(), "tenant-a", command.action(), command.position(), null).digest()).isNotEqualTo(command.digest());
        var adjusted = new BudgetCommand(ID, "tenant-a", BudgetCommand.Action.ADJUST, command.position(), new BudgetCommand.Expected(1, "ledger-v1"));
        assertThat(adjusted.digest()).isNotEqualTo(command.digest());
        assertThat(new BudgetCommand(ID, "tenant-a", adjusted.action(), adjusted.position(), new BudgetCommand.Expected(2, "ledger-v2")).digest()).isNotEqualTo(adjusted.digest());
    }

    @Test
    void onlyInitialFreezeCanOmitPriorLedgerAndInvalidEvidenceFailsImmediately() {
        var command = command("100", null);
        var reopened = new BudgetCommand(ID, "tenant-a", BudgetCommand.Action.FREEZE, command.position(), new BudgetCommand.Expected(1, "v1"));
        assertThat(reopened.digest()).isNotEqualTo(command.digest());
        assertThatThrownBy(() -> BudgetOccupation.begin(new BudgetOperation.Input(reopened, "a".repeat(64)))).isInstanceOf(DomainException.class);
        for (var action : List.of(BudgetCommand.Action.ADJUST, BudgetCommand.Action.RELEASE, BudgetCommand.Action.CONSUME)) {
            assertThatThrownBy(() -> new BudgetCommand(ID, "tenant-a", action, command.position(), null)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new BudgetCommand.Expected(Long.MAX_VALUE, "v1")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> observation(command, BudgetObservation.Status.PENDING, 1L, "v1", NOW, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> observation(command, BudgetObservation.Status.APPLIED, 1L, "v1", null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> observation(command, BudgetObservation.Status.REJECTED, null, null, null, null)).isInstanceOf(DomainException.class);
    }

    @Test
    void appliedRequiresNextLedgerVersionAndNotFoundRequiresQuery() {
        var command = command("100", null);
        assertThat(observation(command, BudgetObservation.Status.APPLIED, 1L, "v1", NOW, null).matches(command, false, NOW)).isTrue();
        assertThat(observation(command, BudgetObservation.Status.APPLIED, 2L, "v2", NOW, null).matches(command, false, NOW)).isFalse();
        assertThat(observation(command, BudgetObservation.Status.APPLIED, 1L, "v1", NOW.plusSeconds(1), null).matches(command, false, NOW)).isFalse();
        var missing = observation(command, BudgetObservation.Status.NOT_FOUND, null, null, null, null);
        assertThat(missing.matches(command, true, NOW)).isTrue(); assertThat(missing.matches(command, false, NOW)).isFalse();
        var release = new BudgetCommand(ID, "tenant-a", BudgetCommand.Action.RELEASE, command.position(), new BudgetCommand.Expected(9, "v9"));
        assertThat(observation(release, BudgetObservation.Status.APPLIED, 10L, "v10", NOW, null).matches(release, false, NOW)).isTrue();
        assertThat(observation(release, BudgetObservation.Status.REJECTED, null, null, null, BudgetObservation.Rejection.BUDGET_INSUFFICIENT).matches(release, false, NOW)).isFalse();
    }

    private BudgetCommand command(String amount, String project) {
        var position = new BudgetPrecheckPort.Request(REPORT, 1, 2, "alice", ENTITY, "CNY", LocalDate.of(2026, 9, 28),
                List.of(new BudgetPrecheckPort.Allocation(1, 1, "TRAVEL", new CostAllocation("研发:中心", project, new Money(new BigDecimal(amount), "CNY")))));
        return new BudgetCommand(ID, "tenant-a", BudgetCommand.Action.FREEZE, position, null);
    }
    private BudgetObservation observation(BudgetCommand command, BudgetObservation.Status status, Long revision, String reference, Instant applied, BudgetObservation.Rejection rejected) {
        return new BudgetObservation(command.id(), command.digest(), status, revision, reference, applied, rejected);
    }
}
