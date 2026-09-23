package io.agentflow.approval.comment;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.Application;
import java.time.Instant;
import java.util.UUID;

/**
 * 申请协作评论是只追加的沟通记录，保留发布时读取的申请上下文，不代表审批意见。
 * @author owlzhangfq@gmail.com
 */
public record ApplicationComment(UUID id, UUID applicationId, String author, String content,
                                 int roundNo, long applicationVersion, ApplicationStatus applicationStatus,
                                 Instant createdAt) {
    /** 从已授权且经用户核对的申请创建不可变记录，不执行审批动作。 */
    public static ApplicationComment record(Application application, long expectedVersion, String author,
                                             String content, Instant time) {
        application.requireCommentContext(expectedVersion);
        return new ApplicationComment(UUID.randomUUID(), application.id(), author, content, application.roundNo(),
                application.version(), application.status(), time.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
}
