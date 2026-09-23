package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 收件箱用例只管理个人消息和阅读状态，不修改审批聚合。
 * @author owlzhangfq@gmail.com
 */
@Service
public class InboxApplicationService {
    private final InboxRepository repository;

    /** 注入消息持久化端口。 */
    public InboxApplicationService(InboxRepository repository) { this.repository = repository; }

    /** 列表只包含本人消息，返回当前未读总数和下一页位置。 */
    @Transactional(readOnly = true)
    public Page list(Actor actor, InboxQueryParameters parameters) {
        List<InboxMessage> found = repository.list(actor, parameters.query());
        int limit = parameters.query().limit();
        List<InboxMessage> items = found.stream().limit(limit).toList();
        return new Page(items, found.size() > limit ? parameters.cursor(items.get(items.size() - 1)) : null,
                repository.unreadCount(actor));
    }

    /** 阅读权限按消息接收人校验，管理员也不能代读其他人的消息。 */
    @Transactional
    public InboxMessage read(Actor actor, UUID id) {
        InboxMessage message = repository.find(actor, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Notification not found"));
        return repository.saveRead(message.markRead(Instant.now()));
    }

    /**
     * 游标分页结果；未读总数不受页面筛选与分页位置限制。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<InboxMessage> items, String nextCursor, long unreadCount) { }
}
