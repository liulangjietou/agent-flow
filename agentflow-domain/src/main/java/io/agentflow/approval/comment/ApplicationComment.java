package io.agentflow.approval.comment;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.Application;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * 申请协作评论是只追加的沟通记录，保留发布时读取的申请上下文，不代表审批意见。
 * @author owlzhangfq@gmail.com
 */
public record ApplicationComment(UUID id, UUID applicationId, String author, String content,
                                 int roundNo, long applicationVersion, ApplicationStatus applicationStatus,
                                 Instant createdAt, List<String> mentions) {
    /** 防止调用方在保存之后修改本次提醒名单。 */
    public ApplicationComment { mentions = List.copyOf(mentions); }

    /** 旧评论没有提醒对象，不能按今天的参与人补造历史。 */
    public ApplicationComment(UUID id, UUID applicationId, String author, String content, int roundNo,
                              long applicationVersion, ApplicationStatus applicationStatus, Instant createdAt) {
        this(id, applicationId, author, content, roundNo, applicationVersion, applicationStatus, createdAt, List.of());
    }
    /** 从已授权且经用户核对的申请创建不可变记录，不执行审批动作。 */
    public static ApplicationComment record(Application application, long expectedVersion, String author,
                                             String content, Instant time) {
        return record(application, expectedVersion, author, content, time, List.of());
    }

    /** 提醒名单是本次发布的不可变记录，后续目录变更不能改写。 */
    public static ApplicationComment record(Application application, long expectedVersion, String author,
                                             String content, Instant time, List<String> mentions) {
        application.requireCommentContext(expectedVersion);
        return new ApplicationComment(UUID.randomUUID(), application.id(), author, content, application.roundNo(),
                application.version(), application.status(), time.truncatedTo(java.time.temporal.ChronoUnit.MICROS), mentions);
    }
}
