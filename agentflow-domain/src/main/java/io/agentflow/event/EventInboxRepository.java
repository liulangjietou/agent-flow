package io.agentflow.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 不可变收件身份与连续处理修订的持久端口。
 * @author owlzhangfq@gmail.com
 */
public interface EventInboxRepository {
    /** 去重范围是租户和已认证来源，其他来源的同名消息互不覆盖。 */
    Optional<EventInboxItem> byEvent(String tenantId, String sourceKey, String eventId);
    /** 读取当前租户中的原收件。 */
    EventInboxItem get(String tenantId, UUID id);
    /** 必须在调用方事务中调用；推进前先锁对应申请及财务聚合。 */
    EventInboxItem lock(String tenantId, UUID id);
    /** 接收事实和初始历史同事务保存。 */
    void create(EventInboxItem item);
    /** 只更新处理修订，拒绝替换不可变输入或原接收时间。 */
    void update(EventInboxItem item);
    /** 有界选取到期标识，单条处理不占用整个队列。 */
    List<Candidate> due(Instant now);
    /** 以同租户原行作游标，返回 limit+1 条。 */
    List<EventInboxItem> page(String tenantId, int limit, UUID beforeId);
    /** 处理历史按真实版本倒序返回 limit+1 条。 */
    List<EventInboxItem> history(String tenantId, UUID id, int limit, Long beforeVersion);
    /** @author owlzhangfq@gmail.com */
    record Candidate(String tenantId, UUID id, String traceId) { }
}
