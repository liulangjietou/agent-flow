package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.event.EventContract;
import java.util.Map;
import java.util.Set;

/**
 * 事件等待只引用一个明确发布的契约版本，不允许通配来源、最新版本或任意引擎表达式。
 * @author owlzhangfq@gmail.com
 */
public record EventWaitPolicy(String contractKey, long contractVersion) {
    public static final String KEY_PROPERTY = "eventContractKey";
    public static final String VERSION_PROPERTY = "eventContractVersion";
    public static final Set<String> PROPERTY_KEYS = Set.of(KEY_PROPERTY, VERSION_PROPERTY);

    /** 契约引用使用目录的同一字面量身份，版本必须明确且为正数。 */
    public EventWaitPolicy {
        try { EventContract.requireKey(contractKey); }
        catch (DomainException failure) { throw invalid(); }
        if (contractVersion < 1) throw invalid();
    }

    /** 属性必须同时存在且版本使用规范十进制，不能在读取时补默认版本。 */
    public static EventWaitPolicy fromProperties(Map<String, String> properties) {
        String key = properties.get(KEY_PROPERTY), version = properties.get(VERSION_PROPERTY);
        if (key == null || version == null) throw new DomainException("EVENT_CONTRACT_REFERENCE_REQUIRED", "An explicit published event contract version is required");
        if (!version.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return new EventWaitPolicy(key, Long.parseLong(version)); }
        catch (NumberFormatException failure) { throw invalid(); }
    }

    private static DomainException invalid() { return new DomainException("EVENT_CONTRACT_REFERENCE_INVALID", "Event contract reference is invalid"); }
}
