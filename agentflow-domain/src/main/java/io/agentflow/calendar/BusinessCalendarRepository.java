package io.agentflow.calendar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 日历当前版本和历史版本共用存储事务；仓储总是要求租户范围。
 * @author owlzhangfq@gmail.com
 */
public interface BusinessCalendarRepository {
    /** 保存首版及不可变历史。 */
    void create(BusinessCalendar calendar);
    /** 乐观锁替换当前指针并追加新历史，不更新旧历史行。 */
    void update(BusinessCalendar calendar, long expectedRevision);
    /** 读取当前版本。 */
    Optional<BusinessCalendar> find(String tenantId, UUID id);
    /** 读取指定历史版本。 */
    Optional<BusinessCalendar> findVersion(String tenantId, UUID id, long revision);
    /** 按业务键稳定分页，返回 limit+1 条供应用层判断下一页。 */
    List<Summary> list(String tenantId, String afterKey, int limit);
    /** 按版本倒序分页，返回 limit+1 条。 */
    List<Summary> versions(String tenantId, UUID id, Long beforeRevision, int limit);

    /**
     * 列表只提供定位和版本信息，不批量加载全年规则。
     * @author owlzhangfq@gmail.com
     */
    record Summary(UUID id, String key, String name, String zoneId, long revision, String updatedBy, Instant updatedAt) { }
}
