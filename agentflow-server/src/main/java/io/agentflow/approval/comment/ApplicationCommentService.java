package io.agentflow.approval.comment;

import io.agentflow.approval.model.Application;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.SubprocessExecutionLocks;
import io.agentflow.notification.InboxMessage;
import io.agentflow.notification.InboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * 编排评论记录与持久化，申请状态规则仍由申请聚合判断。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ApplicationCommentService {
    private final ApplicationCommentRepository repository;
    private final ApprovalApplicationFacade applications;
    private final SubprocessExecutionLocks locks;
    private final CommentMentionDirectory mentions;
    private final InboxRepository inbox;

    /** 注入只追加的评论仓储。 */
    public ApplicationCommentService(ApplicationCommentRepository repository, ApprovalApplicationFacade applications,
                                      SubprocessExecutionLocks locks, CommentMentionDirectory mentions, InboxRepository inbox) {
        this.repository = repository; this.applications = applications; this.locks = locks; this.mentions = mentions; this.inbox = inbox;
    }

    /** 在已经完成实时授权的申请中读取评论页。 */
    @Transactional(readOnly = true)
    public Page list(Application application, CommentQueryParameters parameters) {
        var found = repository.list(application.tenantId(), application.id(), parameters.query());
        var items = found.stream().limit(parameters.query().limit()).toList();
        String cursor = found.size() > items.size() ? parameters.cursor(items.get(items.size() - 1)) : null;
        return new Page(items, cursor);
    }

    /** 与请求幂等记录共用事务；不保存或更新审批聚合。 */
    @Transactional
    public ApplicationComment add(Actor actor, Application application, long expectedVersion, String content, List<String> recipients) {
        var locked = locks.lockPath(application).application();
        // 锁等待期间可能转交或结束；重新授权后才保存正文和提醒，不沿用入口的旧可见性。
        applications.get(locked.id());
        var comment = ApplicationComment.record(locked, expectedVersion, actor.userId(), content, Instant.now(), recipients);
        mentions.requireRecipients(locked, actor, recipients);
        repository.append(actor.tenantId(), comment);
        for (String recipient : recipients) {
            inbox.append("comment:" + comment.id(), new InboxMessage(java.util.UUID.randomUUID(), actor.tenantId(), recipient,
                    locked.id(), locked.title(), locked.businessNo(), InboxMessage.Kind.COMMENT_MENTIONED, actor.userId(),
                    null, null, locked.roundNo(), comment.createdAt(), null, "你在协作评论中被提及，请打开原申请查看。"));
        }
        return comment;
    }

    /**
     * 末页明确返回 nextCursor=null，不用页面条数猜测总量。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<ApplicationComment> items, String nextCursor) { }
}
