package io.agentflow.notification;

import io.agentflow.common.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息归属隔离与持久化端口，不提供跨用户收件箱查询。
 * @author owlzhangfq@gmail.com
 */
public interface InboxRepository {
    /** 与审批使用同一事务追加消息，事件标识和接收人共同去重。 */
    void append(String eventKey, InboxMessage message);
    /** 按当前接收人倒序读取，额外读取一项判断下一页。 */
    List<InboxMessage> list(Actor actor, Query query);
    /** 查询当前接收人的未读总数。 */
    long unreadCount(Actor actor);
    /** 只按当前租户和接收人查找消息。 */
    Optional<InboxMessage> find(Actor actor, UUID id);
    /** 只更新首次阅读时间，并返回并发保存后的实际记录。 */
    InboxMessage saveRead(InboxMessage message);

    /**
     * 已由入口校验的筛选和分页位置。
     * @author owlzhangfq@gmail.com
     */
    record Query(boolean unreadOnly, int limit, Instant beforeTime, UUID beforeId) { }
}
