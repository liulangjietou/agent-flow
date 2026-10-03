package io.agentflow.approval;

import io.agentflow.approval.comment.ApplicationComment;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 评论只读取聚合上下文，不改变审批状态、版本或轮次。
 * @author owlzhangfq@gmail.com
 */
class ApplicationCommentTest {
    @Test
    void recordsCurrentContextAtDatabasePrecisionWithoutChangingAggregate() {
        Application application = application(ApplicationStatus.IN_APPROVAL);
        var time = Instant.parse("2026-09-23T12:34:56.123456789Z");
        var comment = ApplicationComment.record(application, 7, "manager", "核实依据", time);
        assertThat(comment.applicationId()).isEqualTo(application.id());
        assertThat(comment.roundNo()).isEqualTo(2);
        assertThat(comment.applicationVersion()).isEqualTo(7);
        assertThat(comment.applicationStatus()).isEqualTo(ApplicationStatus.IN_APPROVAL);
        assertThat(comment.createdAt()).isEqualTo(Instant.parse("2026-09-23T12:34:56.123456Z"));
        assertThat(application.version()).isEqualTo(7);
        assertThat(application.roundNo()).isEqualTo(2);
        assertThat(application.status()).isEqualTo(ApplicationStatus.IN_APPROVAL);
    }

    @Test
    void staleApplicationCannotSilentlyRebindCommentToANewContext() {
        assertThatThrownBy(() -> ApplicationComment.record(application(ApplicationStatus.IN_APPROVAL), 6, "alice", "旧草稿", Instant.now()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CONCURRENCY_CONFLICT");
    }

    @Test
    void mentionRecipientsAreImmutableAndLegacyCommentsHaveNoInventedRecipients() {
        var recipients = new java.util.ArrayList<>(java.util.List.of("finance"));
        var comment = ApplicationComment.record(application(ApplicationStatus.IN_APPROVAL), 7, "alice", "核对", Instant.now(), recipients);
        recipients.add("manager");
        assertThat(comment.mentions()).containsExactly("finance");
        assertThatThrownBy(() -> comment.mentions().add("bob")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ApplicationComment.record(application(ApplicationStatus.IN_APPROVAL), 7, "alice", "旧格式", Instant.now()).mentions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = "IN_APPROVAL", mode = EnumSource.Mode.EXCLUDE)
    void onlyTheConfirmedInApprovalScopeIsWritable(ApplicationStatus status) {
        assertThatThrownBy(() -> ApplicationComment.record(application(status), 7, "alice", "评论", Instant.now()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("DOMAIN_RULE_VIOLATION");
    }

    private Application application(ApplicationStatus status) {
        return Application.restore(UUID.randomUUID(), "demo", "COMMENT-1", "leave", 1, "alice", "评论测试", Map.of(), status, 2, 7, null);
    }
}
