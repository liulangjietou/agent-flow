package io.agentflow.approval.model;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 增减签约束只依赖实际责任事实，不依赖 Flowable 或组织目录。
 * @author owlzhangfq@gmail.com
 */
class CountersignMembershipTest {
    @Test
    void additionsAndRemovalsDoNotChangeCompletedDecisions() {
        var state = new CountersignMembership(3, 1, List.of(member("a"), member("b")), List.of("done"));
        state.requireAddition("a", "new");
        assertThat(state.requireRemoval("a", "b")).isEqualTo(member("b"));
        assertThat(state.completedUsers()).containsExactly("done");
        assertThat(state.total()).isEqualTo(3);
    }

    @Test
    void existingPendingOrCompletedMemberCannotBeAddedTwice() {
        var state = new CountersignMembership(2, 1, List.of(member("a")), List.of("done"));
        for (String target : List.of("a", "done")) rejects(() -> state.requireAddition("a", target), "COUNTERSIGN_MEMBER_EXISTS");
    }

    @Test
    void finalPendingTaskCannotBeRemovedEvenAfterOtherApprovals() {
        var state = new CountersignMembership(2, 1, List.of(member("a")), List.of("done"));
        rejects(() -> state.requireRemoval("a", "a"), "COUNTERSIGN_LAST_MEMBER");
        rejects(() -> state.requireRemoval("a", "done"), "COUNTERSIGN_MEMBER_UNAVAILABLE");
    }

    @Test
    void actingMemberRetainsTheirOwnResponsibility() {
        var state = new CountersignMembership(2, 0, List.of(member("a"), member("b")), List.of());
        rejects(() -> state.requireRemoval("a", "a"), "COUNTERSIGN_SELF_REMOVAL");
        rejects(() -> state.requireAddition("unknown", "new"), "COUNTERSIGN_MEMBER_UNAVAILABLE");
    }

    @Test
    void delegatedAssistanceCannotChangeOrLoseResponsibility() {
        var state = new CountersignMembership(2, 0,
                List.of(member("a"), new CountersignMembership.Member("b", "b", "helper", true)), List.of());
        rejects(() -> state.requireAddition("b", "new"), "TASK_DELEGATION_PENDING");
        rejects(() -> state.requireRemoval("b", "a"), "TASK_DELEGATION_PENDING");
        rejects(() -> state.requireRemoval("a", "b"), "TASK_DELEGATION_PENDING");
    }

    @Test
    void memberLimitDoesNotPreventRemovingExistingLargeLists() {
        var members = IntStream.range(0, CountersignMembership.MAX_MEMBERS).mapToObj(index -> member("u" + index)).toList();
        var state = new CountersignMembership(members.size(), 0, members, List.of());
        rejects(() -> state.requireAddition("u0", "extra"), "COUNTERSIGN_MEMBER_LIMIT");
        assertThat(state.requireRemoval("u0", "u1").user()).isEqualTo("u1");
    }

    @Test
    void countMismatchMissingHistoryOrDuplicateResponsibilityFailsClosed() {
        rejects(() -> new CountersignMembership(3, 1, List.of(member("a")), List.of("done")), "COUNTERSIGN_STATE_INVALID");
        rejects(() -> new CountersignMembership(2, 1, List.of(member("a")), List.of()), "COUNTERSIGN_STATE_INVALID");
        rejects(() -> new CountersignMembership(2, 0, List.of(member("a"), member("a")), List.of()), "COUNTERSIGN_STATE_INVALID");
        rejects(() -> new CountersignMembership(2, 1, List.of(member("a")), List.of("a")), "COUNTERSIGN_STATE_INVALID");
        rejects(() -> new CountersignMembership(1, 0, List.of(new CountersignMembership.Member("a", "a", "b", false)), List.of()), "COUNTERSIGN_STATE_INVALID");
    }

    private CountersignMembership.Member member(String user) { return new CountersignMembership.Member(user, user, user, false); }
    private void rejects(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
