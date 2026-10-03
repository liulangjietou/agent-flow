package io.agentflow.organization;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 代理时间边界、不可变范围和具名撤销的领域验证，不依赖定时调度。
 * @author owlzhangfq@gmail.com
 */
class ApprovalProxyTest {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant START = CREATED.plusSeconds(60);
    private static final Instant END = START.plusSeconds(60);
    private final UUID principal = UUID.randomUUID();
    private final UUID substitute = UUID.randomUUID();

    @Test
    void startsInclusivelyAndExpiresExclusivelyWithoutChangingStoredState() {
        var value = grant(START, END, CREATED);
        assertThat(value.statusAt(START.minusNanos(1))).isEqualTo(ApprovalProxy.Status.SCHEDULED);
        assertThat(value.statusAt(START)).isEqualTo(ApprovalProxy.Status.ACTIVE);
        assertThat(value.statusAt(END.minusNanos(1))).isEqualTo(ApprovalProxy.Status.ACTIVE);
        assertThat(value.statusAt(END)).isEqualTo(ApprovalProxy.Status.EXPIRED);
        assertThat(value.statusAt(END.plusSeconds(1))).isEqualTo(ApprovalProxy.Status.EXPIRED);
        assertThat(value.revision()).isEqualTo(1);
    }

    @Test
    void pastStartNeverGrantsPermissionBeforeCreation() {
        var value = grant(CREATED.minusSeconds(60), END, CREATED);
        assertThat(value.statusAt(CREATED.minusNanos(1))).isEqualTo(ApprovalProxy.Status.SCHEDULED);
        assertThat(value.statusAt(CREATED)).isEqualTo(ApprovalProxy.Status.ACTIVE);
    }

    @Test
    void revocationPreservesOriginalScopeAndIsTerminalEvenBeforeScheduledStart() {
        var original = grant(START, END, CREATED);
        var revoked = original.revoke(1, "issuer:admin/中文", " 取消代理 ", CREATED.plusSeconds(1));
        assertThat(revoked.id()).isEqualTo(original.id());
        assertThat(revoked.definitionId()).isEqualTo(original.definitionId());
        assertThat(revoked.principalId()).isEqualTo(principal);
        assertThat(revoked.substituteId()).isEqualTo(substitute);
        assertThat(revoked.startsAt()).isEqualTo(START);
        assertThat(revoked.endsAt()).isEqualTo(END);
        assertThat(revoked.reason()).isEqualTo(original.reason());
        assertThat(revoked.createdAt()).isEqualTo(CREATED);
        assertThat(revoked.createdBy()).isEqualTo(original.createdBy());
        assertThat(revoked.revocation().actor()).isEqualTo("issuer:admin/中文");
        assertThat(revoked.revocation().reason()).isEqualTo("取消代理");
        assertThat(revoked.statusAt(START)).isEqualTo(ApprovalProxy.Status.REVOKED);
        assertThat(original.revocation()).isNull();
        assertCode(() -> revoked.revoke(1, "admin", "旧版本", START), "CONCURRENCY_CONFLICT");
        assertCode(() -> revoked.revoke(2, "admin", "再次撤销", START), "APPROVAL_PROXY_REVOKED");
    }

    @Test
    void rejectsSelfProxyMissingScopeEmptyReasonsAndInvalidTimeWindows() {
        assertCode(() -> new ApprovalProxy(UUID.randomUUID(), UUID.randomUUID(), principal, principal,
                START, END, "出差", "admin", CREATED, 1, null), "INVALID_APPROVAL_PROXY");
        assertCode(() -> new ApprovalProxy(UUID.randomUUID(), null, principal, substitute,
                START, END, "出差", "admin", CREATED, 1, null), "INVALID_APPROVAL_PROXY");
        assertCode(() -> new ApprovalProxy(UUID.randomUUID(), UUID.randomUUID(), principal, substitute,
                START, END, " ", "admin", CREATED, 1, null), "INVALID_APPROVAL_PROXY");
        assertCode(() -> grant(END, START, CREATED), "INVALID_APPROVAL_PROXY");
        assertCode(() -> grant(START, START, CREATED), "INVALID_APPROVAL_PROXY");
        assertCode(() -> grant(START, END, END), "INVALID_APPROVAL_PROXY");
        assertCode(() -> grant(null, END, CREATED), "INVALID_APPROVAL_PROXY");
        assertCode(() -> grant(START, Instant.MAX, CREATED), "INVALID_APPROVAL_PROXY");
    }

    @Test
    void normalizesSubMicrosecondInputsBeforeJudgingWindowAndPersistence() {
        var value = grant(START.plusNanos(123456), END.plusNanos(987654), CREATED.plusNanos(999));
        assertThat(value.startsAt()).isEqualTo(START.plusNanos(123000));
        assertThat(value.endsAt()).isEqualTo(END.plusNanos(987000));
        assertThat(value.createdAt()).isEqualTo(CREATED);
        assertCode(() -> grant(START.plusNanos(100), START.plusNanos(999), CREATED), "INVALID_APPROVAL_PROXY");
    }

    @Test
    void restoredRevocationCannotLoseItsActorOrPredateCreation() {
        var value = grant(START, END, CREATED);
        assertCode(() -> value.revoke(1, "", "撤销", START), "INVALID_APPROVAL_PROXY");
        assertCode(() -> value.revoke(1, "admin", " ", START), "INVALID_APPROVAL_PROXY");
        assertCode(() -> value.revoke(1, "admin", "撤销", CREATED.minusSeconds(1)), "INVALID_APPROVAL_PROXY");
        assertCode(() -> new ApprovalProxy(value.id(), value.definitionId(), principal, substitute,
                START, END, value.reason(), value.createdBy(), CREATED, 2, null), "INVALID_APPROVAL_PROXY");
    }

    private ApprovalProxy grant(Instant start, Instant end, Instant created) {
        return new ApprovalProxy(UUID.randomUUID(), UUID.randomUUID(), principal, substitute,
                start, end, " 休假期间代办 ", "administrator", created, 1, null);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code);
    }
}
