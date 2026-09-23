package io.agentflow.approval.comment;

import io.agentflow.approval.model.Application;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
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

    /** 注入只追加的评论仓储。 */
    public ApplicationCommentService(ApplicationCommentRepository repository) { this.repository = repository; }

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
    public ApplicationComment add(Actor actor, Application application, long expectedVersion, String content) {
        var comment = ApplicationComment.record(application, expectedVersion, actor.userId(), content, Instant.now());
        repository.append(actor.tenantId(), comment);
        return comment;
    }

    /**
     * 末页明确返回 nextCursor=null，不用页面条数猜测总量。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<ApplicationComment> items, String nextCursor) { }
}
