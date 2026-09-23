package io.agentflow.approval.comment;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 用户提交的游标即使编码合法，也必须满足数据库时间边界与当前身份绑定。
 * @author owlzhangfq@gmail.com
 */
class CommentQueryParametersTest {
    private final UUID applicationId = UUID.randomUUID();
    private final Actor actor = new Actor("demo", "alice", Set.of("EMPLOYEE"));

    @ParameterizedTest
    @ValueSource(strings = {"-1000000000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z", "+10000-01-01T00:00:00Z"})
    void rejectsOutOfDatabaseRangeTimesBeforeTheyReachJdbc(String time) {
        String cursor = cursor(Instant.parse(time));
        assertThatThrownBy(() -> CommentQueryParameters.parse(actor, applicationId, Map.of("cursor", cursor)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_COMMENT_QUERY");
    }

    @Test
    void preservesMicrosecondsAndRejectsChangedTenantOrRoles() {
        Instant time = Instant.parse("2026-09-23T12:00:00.123456Z");
        String cursor = cursor(time);
        assertThat(CommentQueryParameters.parse(actor, applicationId, Map.of("cursor", cursor)).query().beforeTime()).isEqualTo(time);
        for (Actor other : new Actor[]{new Actor("other", "alice", Set.of("EMPLOYEE")), new Actor("demo", "alice", Set.of("ADMIN"))}) {
            assertThatThrownBy(() -> CommentQueryParameters.parse(other, applicationId, Map.of("cursor", cursor)))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("INVALID_COMMENT_QUERY");
        }
    }

    private String cursor(Instant time) {
        var parameters = CommentQueryParameters.parse(actor, applicationId, Map.of());
        return parameters.cursor(new ApplicationComment(UUID.randomUUID(), applicationId, "alice", "内容", 1, 2, ApplicationStatus.IN_APPROVAL, time));
    }
}
