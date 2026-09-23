package io.agentflow.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 日历用例编排：版本存储与幂等成功响应共用事务，期限计算由领域服务完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BusinessCalendarService {
    private final BusinessCalendarRepository repository;

    /** 注入租户隔离的日历仓储。 */
    public BusinessCalendarService(BusinessCalendarRepository repository) { this.repository = repository; }

    /** 创建管理员显式配置的首版日历。 */
    @Transactional
    public BusinessCalendar create(Actor actor, String key, String name, CalendarRules rules) {
        var calendar = BusinessCalendar.create(actor.tenantId(), key, name, rules, actor.userId(), Instant.now());
        repository.create(calendar);
        return calendar;
    }

    /** 保留旧规则与操作者，以用户核对过的修订号防止覆盖。 */
    @Transactional
    public BusinessCalendar update(Actor actor, UUID id, String name, CalendarRules rules, long expectedRevision) {
        var calendar = get(actor.tenantId(), id).revise(name, rules, expectedRevision, actor.userId(), Instant.now());
        repository.update(calendar, expectedRevision);
        return calendar;
    }

    /** 读取当前租户日历，不存在与跨租户使用相同错误。 */
    @Transactional(readOnly = true)
    public BusinessCalendar get(String tenantId, UUID id) {
        return repository.find(tenantId, id).orElseThrow(BusinessCalendarService::missing);
    }

    /** 读取不可变历史；后续修改当前规则不能覆盖此结果。 */
    @Transactional(readOnly = true)
    public BusinessCalendar version(String tenantId, UUID id, long revision) {
        return repository.findVersion(tenantId, id, revision).orElseThrow(BusinessCalendarService::missing);
    }

    /** 按业务键分页，页面只读取摘要。 */
    @Transactional(readOnly = true)
    public CalendarPage list(String tenantId, String afterKey, int limit) {
        var found = repository.list(tenantId, afterKey, limit);
        var items = found.stream().limit(limit).toList();
        return new CalendarPage(items, found.size() > items.size() ? items.get(items.size() - 1).key() : null);
    }

    /** 历史分页先核对资源存在，不能用空历史区分跨租户数据。 */
    @Transactional(readOnly = true)
    public VersionPage versions(String tenantId, UUID id, Long beforeRevision, int limit) {
        get(tenantId, id);
        var found = repository.versions(tenantId, id, beforeRevision, limit);
        var items = found.stream().limit(limit).toList();
        return new VersionPage(items, found.size() > items.size() ? items.get(items.size() - 1).revision() : null);
    }

    /** 对明确选择的历史版本试算，返回所用修订而不读取或修改实际任务。 */
    @Transactional(readOnly = true)
    public Calculation calculate(String tenantId, UUID id, long revision, LocalDateTime startLocal, CalendarRules.OverlapChoice overlapChoice, int workingMinutes) {
        var calendar = version(tenantId, id, revision);
        return new Calculation(id, revision, BusinessDeadline.calculate(calendar.rules(), startLocal, overlapChoice, workingMinutes));
    }

    private static DomainException missing() { return new DomainException("NOT_FOUND", "Calendar or revision was not found"); }

    /**
     * 当前目录的稳定键分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CalendarPage(List<BusinessCalendarRepository.Summary> items, String nextAfterKey) { }
    /**
     * 历史修订的倒序分页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VersionPage(List<BusinessCalendarRepository.Summary> items, Long nextBeforeRevision) { }
    /**
     * 试算结果固定说明日历及修订。
     * @author owlzhangfq@gmail.com
     */
    public record Calculation(UUID calendarId, long revision, BusinessDeadline.Result deadline) { }
}
