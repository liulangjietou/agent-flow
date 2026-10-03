package io.agentflow.event;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 已发布的事件白名单契约。来源和事件类型固定在版本上，后续发布不能重写旧流程的引用。
 * @author owlzhangfq@gmail.com
 */
public record EventContract(String tenantId, String key, long version, String name, String sourceKey,
                            String eventType, int envelopeVersion, String publishedBy, Instant publishedAt,
                            String publicationReason) {
    public static final int ENVELOPE_VERSION = 1;
    public static final int MAX_REASON_LENGTH = 2000;
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");
    private static final int MAX_NAME_LENGTH = 200;

    /** 明确发布一个版本；版本分配与租户内唯一性由仓储在同一事务保护。 */
    public static EventContract publish(String tenantId, String key, long version, String name, String sourceKey,
                                        String eventType, String actor, String reason, Instant now) {
        requireKey(key);
        if (sourceKey == null || !KEY.matcher(sourceKey).matches() || eventType == null || !EVENT_TYPE.matcher(eventType).matches()
                || version < 1 || StringUtils.isBlank(name) || name.length() > MAX_NAME_LENGTH
                || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return new EventContract(tenantId, key, version, name.strip(), sourceKey, eventType, ENVELOPE_VERSION,
                actor, now.truncatedTo(ChronoUnit.MICROS), requireReason(reason));
    }

    /** 契约键是明确的字面量，不接受表达式、URL 或自动归一化后的另一身份。 */
    public static void requireKey(String key) { if (key == null || !KEY.matcher(key).matches()) throw invalid(); }

    /** 发布和启停都必须留下操作者明确填写的原因。 */
    static String requireReason(String reason) {
        if (StringUtils.isBlank(reason) || reason.length() > MAX_REASON_LENGTH) throw invalid();
        return reason.strip();
    }

    private static DomainException invalid() { return new DomainException("INVALID_EVENT_CONTRACT", "Event contract settings are invalid"); }
}
