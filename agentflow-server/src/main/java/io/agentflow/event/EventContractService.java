package io.agentflow.event;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/**
 * 白名单发布和启停与追加历史、幂等回执共同提交；本服务不发出事件或修改流程实例。
 * @author owlzhangfq@gmail.com
 */
@Service
public class EventContractService {
    private final EventContractRepository repository;

    /** 注入租户隔离的发布仓储。 */
    public EventContractService(EventContractRepository repository) { this.repository = repository; }

    /** 发布相邻版本，旧正文和旧版可用性均保持原值。 */
    @Transactional
    public EventContractRepository.Version publish(Actor actor, String key, long expectedVersion, String name,
                                                    String sourceKey, String eventType, String reason) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new DomainException("INVALID_EVENT_CONTRACT", "Expected event contract version is invalid");
        }
        var value = EventContract.publish(actor.tenantId(), key, expectedVersion + 1, name, sourceKey, eventType, actor.userId(), reason, Instant.now());
        repository.publish(value, expectedVersion);
        return new EventContractRepository.Version(value, EventContractAvailability.published(value));
    }

    /** 当前目录始终显示真实最新发布版本，不隐藏停用版本或擅自选择旧版。 */
    @Transactional(readOnly = true)
    public EventContractViews.Directory list(String tenantId, EventContractQuery query) {
        var found = repository.list(tenantId, query.afterKey(), query.limit());
        var items = found.stream().limit(query.limit()).map(EventContractViews.Option::from).toList();
        return new EventContractViews.Directory(items, found.size() > items.size() ? items.get(items.size() - 1).key() : null);
    }

    /** 查询不存在和跨租户使用同一错误。 */
    @Transactional(readOnly = true)
    public EventContractRepository.Version version(String tenantId, String key, long version) {
        EventContract.requireKey(key); EventContractQuery.requireVersion(version);
        return repository.find(tenantId, key, version).orElseThrow(EventContractService::missing);
    }

    /** 分页读取所有明确发布版本，不把停用视为删除。 */
    @Transactional(readOnly = true)
    public EventContractViews.Versions versions(String tenantId, String key, EventContractQuery query) {
        EventContract.requireKey(key);
        repository.latest(tenantId, key).orElseThrow(EventContractService::missing);
        var found = repository.versions(tenantId, key, query.before(), query.limit());
        var items = found.stream().limit(query.limit()).map(EventContractViews.Option::from).toList();
        return new EventContractViews.Versions(items, found.size() > items.size() ? items.get(items.size() - 1).version() : null);
    }

    /** 停用或恢复当前选定版本，领域对象判定状态并生成下一修订。 */
    @Transactional
    public EventContractRepository.Version changeAvailability(Actor actor, String key, long version, long expectedRevision, boolean enabled, String reason) {
        var current = version(actor.tenantId(), key, version);
        var state = current.availability().change(expectedRevision, enabled, actor.userId(), reason, Instant.now());
        repository.changeAvailability(state, expectedRevision);
        return new EventContractRepository.Version(current.contract(), state);
    }

    /** 发布和启停共用一条连续审计链，先验证具体版本再读取历史。 */
    @Transactional(readOnly = true)
    public EventContractViews.History history(String tenantId, String key, long version, EventContractQuery query) {
        version(tenantId, key, version);
        var found = repository.history(tenantId, key, version, query.before(), query.limit());
        var items = found.stream().limit(query.limit()).map(EventContractViews.Availability::from).toList();
        return new EventContractViews.History(items, found.size() > items.size() ? items.get(items.size() - 1).revision() : null);
    }

    private static DomainException missing() { return new DomainException("NOT_FOUND", "Event contract version not found"); }
}
