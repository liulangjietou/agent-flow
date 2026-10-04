package io.agentflow.organization;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 读取租约、具名人工决定、来源游标和持久化恢复的组织同步状态验证。
 * @author owlzhangfq@gmail.com
 */
class OrganizationSyncBatchTest {
    private static final Instant AT = Instant.parse("2026-10-04T04:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test void receivedFactsStayPendingUntilExplicitApplicationAndCursorCommit() {
        var batch = received(); var source = source();
        assertThat(batch.pending()).isTrue(); assertThat(source.appliedRevision()).isZero();
        var applied = new OrganizationSyncBatch.Applied(DIGEST, 5, 8);
        batch.apply(3, 0, applied, "another-admin", "核对人员及任职", AT.plusSeconds(3));
        assertThat(batch.pending()).isFalse(); assertThat(batch.state().decision().actor()).isEqualTo("another-admin");
        var updated = source.applied(batch, 1); assertThat(updated.appliedRevision()).isEqualTo(1);
        assertThat(updated.lastAppliedBatchId()).isEqualTo(batch.context().id()); assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.registeredBy()).isEqualTo(source.registeredBy()); assertThat(source.appliedRevision()).isZero();
        assertThat(OrganizationSyncBatch.restore(batch.context(), batch.state()).state()).isEqualTo(batch.state());
        assertCode(() -> source.applied(batch, 2), "CONCURRENCY_CONFLICT");
    }

    @ParameterizedTest @EnumSource(OrganizationSyncBatch.Failure.class)
    void failuresAreStableAndDoNotAdvanceSourceCursor(OrganizationSyncBatch.Failure failure) {
        var batch = fetching(); batch.fail(2, failure, AT.plusSeconds(2));
        assertThat(batch.pending()).isFalse(); assertThat(batch.state().failure()).isEqualTo(failure);
        assertThat(OrganizationSyncBatch.restore(batch.context(), batch.state()).state()).isEqualTo(batch.state());
        assertCode(() -> source().applied(batch, 1), "ORGANIZATION_SYNC_SOURCE_CHANGED");
        assertCode(() -> batch.receive(3, delta(), AT.plusSeconds(3)), "ORGANIZATION_SYNC_STATE_CONFLICT");
        assertCode(() -> batch.start(3, AT.plusSeconds(3), AT.plusSeconds(30)), "ORGANIZATION_SYNC_STATE_CONFLICT");
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "FETCHING", "RECEIVED"})
    void cancellationPreservesOriginalFactsAndRejectsLateResponse(String stage) {
        var batch = stage.equals("QUEUED") ? queued() : stage.equals("FETCHING") ? fetching() : received();
        var old = batch.state(); batch.cancel(old.version(), "reviewer", "取消后重新读取", AT.plusSeconds(3));
        assertThat(batch.pending()).isFalse(); assertThat(batch.state().delta()).isEqualTo(old.delta());
        assertThat(batch.state().decision().applied()).isNull();
        assertThat(OrganizationSyncBatch.restore(batch.context(), batch.state()).state()).isEqualTo(batch.state());
        assertCode(() -> batch.receive(batch.state().version(), delta(), AT.plusSeconds(4)), "ORGANIZATION_SYNC_STATE_CONFLICT");
        assertCode(() -> source().applied(batch, 1), "ORGANIZATION_SYNC_SOURCE_CHANGED");
    }

    @Test void originalLeaseSurvivesRestoreAndRejectsBoundaryAndLateResult() {
        var before = fetching(); var batch = OrganizationSyncBatch.restore(before.context(), before.state());
        assertThat(batch.expired(AT.plusSeconds(30).minusNanos(1))).isFalse(); assertThat(batch.expired(AT.plusSeconds(30))).isTrue();
        assertCode(() -> batch.receive(2, delta(), AT.plusSeconds(30)), "ORGANIZATION_SYNC_LEASE_EXPIRED");
        assertThat(batch.state()).isEqualTo(before.state()); batch.fail(2, OrganizationSyncBatch.Failure.SOURCE_TIMEOUT, AT.plusSeconds(30));
        assertThat(batch.state().leaseUntil()).isEqualTo(AT.plusSeconds(30));
    }

    @ParameterizedTest @ValueSource(strings = {"source", "cursor"})
    void refusesDataForAnotherSourceOrCursor(String variant) {
        var batch = fetching(); var changed = new OrganizationSyncDelta(variant.equals("source") ? "other" : "hr", variant.equals("cursor") ? 1 : 0, 2, List.of(), List.of(), List.of());
        assertCode(() -> batch.receive(2, changed, AT.plusSeconds(2)), "ORGANIZATION_SYNC_SOURCE_CHANGED");
        assertThat(batch.state().status()).isEqualTo(OrganizationSyncBatch.Status.FETCHING);
    }

    @Test void refusesApplicationAfterCursorChangedAndBeforeReception() {
        var batch = received(); var applied = new OrganizationSyncBatch.Applied(DIGEST, 1, 1);
        assertCode(() -> batch.apply(3, 1, applied, "admin", null, AT.plusSeconds(3)), "ORGANIZATION_SYNC_SOURCE_CHANGED");
        assertCode(() -> batch.apply(3, 0, applied, "admin", null, AT), "INVALID_ORGANIZATION_SYNC_DATA");
        assertThat(batch.state().status()).isEqualTo(OrganizationSyncBatch.Status.RECEIVED);
    }

    @Test void emptyBatchCanConfirmSameCursorWithoutInventingDirectoryChanges() {
        var batch = fetching(); batch.receive(2, new OrganizationSyncDelta("hr", 0, 0, List.of(), List.of(), List.of()), AT.plusSeconds(2));
        batch.apply(3, 0, new OrganizationSyncBatch.Applied(DIGEST, 1, 1), "admin", null, AT.plusSeconds(3));
        var updated = source().applied(batch, 1); assertThat(updated.appliedRevision()).isZero(); assertThat(updated.version()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"version", "received-time", "lease", "failure", "finished", "decision"})
    void rejectsInconsistentPersistedState(String variant) {
        var batch = received(); var s = batch.state();
        var corrupt = new OrganizationSyncBatch.State(s.status(), variant.equals("version") ? 4 : s.version(), s.startedAt(),
                variant.equals("lease") ? AT : s.leaseUntil(), variant.equals("received-time") ? AT.minusSeconds(1) : s.receivedAt(), s.delta(),
                variant.equals("failure") ? OrganizationSyncBatch.Failure.SOURCE_TIMEOUT : null, variant.equals("finished") ? AT.plusSeconds(3) : null,
                variant.equals("decision") ? new OrganizationSyncBatch.Decision("admin", null, AT, null) : null);
        assertThatThrownBy(() -> OrganizationSyncBatch.restore(batch.context(), corrupt)).isInstanceOfAny(DomainException.class, IllegalStateException.class);
    }

    @Test void retryCreatesAnotherContextWithoutReusingBatchIdentity() {
        var original = queued(); var next = new OrganizationSyncBatch.Context(UUID.randomUUID(), "tenant", "hr", 7, DIGEST, "admin", AT.plusSeconds(60), original.context().id());
        assertThat(new OrganizationSyncBatch(next).state().version()).isEqualTo(1);
        assertCode(() -> new OrganizationSyncBatch.Context(original.context().id(), "tenant", "hr", 0, DIGEST, "admin", AT, original.context().id()), "INVALID_ORGANIZATION_SYNC_DATA");
    }

    @Test void bindingAdvancesOnlyForNewSourceRevisionAndPreservesEntityIdentity() {
        var first = new OrganizationSyncBinding("tenant", "hr", new OrganizationSyncKey(OrganizationSyncKey.Kind.PERSON, "source-person"), UUID.randomUUID(), 3, 7, 1, UUID.randomUUID(), AT);
        var next = first.applied(1, 5, 8, UUID.randomUUID(), AT.plusSeconds(1));
        assertThat(next.localId()).isEqualTo(first.localId()); assertThat(next.key()).isEqualTo(first.key()); assertThat(next.version()).isEqualTo(2);
        assertCode(() -> first.applied(1, 2, 8, UUID.randomUUID(), AT), "INVALID_ORGANIZATION_SYNC_DATA");
        assertCode(() -> first.applied(1, 3, 7, UUID.randomUUID(), AT), "INVALID_ORGANIZATION_SYNC_DATA");
        assertCode(() -> first.applied(2, 5, 8, UUID.randomUUID(), AT), "CONCURRENCY_CONFLICT");
    }

    private static OrganizationSyncSource source() { return new OrganizationSyncSource("tenant", "hr", 0, 1, null, "admin", AT); }
    private static OrganizationSyncDelta delta() { return new OrganizationSyncDelta("hr", 0, 1, List.of(), List.of(), List.of()); }
    private static OrganizationSyncBatch queued() { return new OrganizationSyncBatch(new OrganizationSyncBatch.Context(UUID.randomUUID(), "tenant", "hr", 0, DIGEST, "admin", AT, null)); }
    private static OrganizationSyncBatch fetching() { var batch = queued(); batch.start(1, AT.plusSeconds(1), AT.plusSeconds(30)); return batch; }
    private static OrganizationSyncBatch received() { var batch = fetching(); batch.receive(2, delta(), AT.plusSeconds(2)); return batch; }
    private static void assertCode(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code").isEqualTo(code); }
}
