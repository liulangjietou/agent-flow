package io.agentflow.event;

import java.util.List;
import java.util.Optional;

/**
 * 事件契约、精确版本的可用性及追加历史共用事务，任何读写都限定租户。
 * @author owlzhangfq@gmail.com
 */
public interface EventContractRepository {
    /** 分配相邻发布版本并保存正文和首次可用性；旧版正文没有更新入口。 */
    void publish(EventContract contract, long expectedVersion);
    /** 只更新启停状态并追加历史，以原修订避免覆盖并发管理员操作。 */
    void changeAvailability(EventContractAvailability availability, long expectedRevision);
    /** 读取指定发布版本；绝不回退到最新版。 */
    Optional<Version> find(String tenantId, String key, long version);
    /** 在调用方事务中锁定精确版本，与发布、发起及事件推进的可用性判断保持原子性。 */
    Optional<Version> lockVersion(String tenantId, String key, long version);
    /** 读取该契约最新发布版本，不跳过已停用的版本。 */
    Optional<Version> latest(String tenantId, String key);
    /** 按业务键读取最新版本，返回 limit+1 条供调用方构造游标。 */
    List<Version> list(String tenantId, String afterKey, int limit);
    /** 按发布版本倒序，返回 limit+1 条。 */
    List<Version> versions(String tenantId, String key, Long beforeVersion, int limit);
    /** 原始发布及所有启停决定按修订倒序保留，返回 limit+1 条。 */
    List<EventContractAvailability> history(String tenantId, String key, long version, Long beforeRevision, int limit);

    /**
     * 发布正文和当前可用性共同读取，不把可用性修订当成新的契约版本。
     * @author owlzhangfq@gmail.com
     */
    record Version(EventContract contract, EventContractAvailability availability) { }
}
