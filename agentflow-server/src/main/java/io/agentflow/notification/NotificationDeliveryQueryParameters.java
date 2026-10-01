package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

/** 查询入口一次校验；游标绑定本人、筛选和历史所属投递，不接受身份覆盖。 @author owlzhangfq@gmail.com */
public final class NotificationDeliveryQueryParameters {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private NotificationDeliveryQueryParameters() { }

    /** 列表以不可变创建时间和标识分页；当前状态筛选不承诺跨页状态快照。 */
    public static Search search(Actor actor, MultiValueMap<String,String> raw, JsonUtil json) {
        var values = fields(raw, Set.of("channel", "status", "limit", "cursor"));
        try {
            String channel = values.getOrDefault("channel", ""), status = values.getOrDefault("status", "");
            if (!channel.isEmpty()) NotificationChannel.valueOf(channel);
            if (!status.isEmpty()) NotificationDeliveryProgress.Status.valueOf(status);
            String context = context(actor, List.of("notification-deliveries", channel, status), json);
            var parts = cursor(values.get("cursor"), context, 3);
            Instant beforeTime = parts == null ? null : Instant.parse(parts[1]);
            if (beforeTime != null && (beforeTime.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || beforeTime.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z")))) throw invalid();
            UUID beforeId = parts == null ? null : uuid(parts[2]);
            return new Search(channel, status, limit(values), beforeTime, beforeId, context);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) { throw invalid(); }
    }

    /** 历史游标同时绑定原投递，按不可变版本从新到旧读取。 */
    public static History history(Actor actor, UUID deliveryId, MultiValueMap<String,String> raw, JsonUtil json) {
        var values = fields(raw, Set.of("limit", "cursor"));
        try {
            String context = context(actor, List.of("notification-history", deliveryId.toString()), json);
            var parts = cursor(values.get("cursor"), context, 2);
            Long beforeVersion = parts == null ? null : Long.parseLong(parts[1]);
            if (beforeVersion != null && beforeVersion < 1) throw invalid();
            return new History(limit(values), beforeVersion, context);
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    /** 详情和写入口不接受筛选、身份、目的地或重复查询参数。 */
    public static void noQuery(MultiValueMap<String,String> raw) { fields(raw, Set.of()); }

    private static Map<String,String> fields(MultiValueMap<String,String> raw, Set<String> supported) {
        if (!supported.containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        return raw.toSingleValueMap();
    }
    private static int limit(Map<String,String> values) {
        if (!values.containsKey("limit")) return DEFAULT_LIMIT;
        String raw = values.get("limit"); if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
        int limit = Integer.parseInt(raw); if (limit > MAX_LIMIT) throw invalid(); return limit;
    }
    private static String context(Actor actor, List<String> scope, JsonUtil json) {
        return UUID.nameUUIDFromBytes(json.write(List.of(actor.tenantId(), actor.userId(), scope)).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String[] cursor(String raw, String context, int length) {
        if (raw == null) return null;
        if (raw.isEmpty() || raw.length() > MAX_CURSOR_LENGTH) throw invalid();
        var parts = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split("\n", -1);
        if (parts.length != length || !context.equals(parts[0])) throw invalid(); return parts;
    }
    private static UUID uuid(String raw) { if (!raw.matches(UUID_PATTERN)) throw invalid(); return UUID.fromString(raw); }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static DomainException invalid() { return new DomainException("INVALID_NOTIFICATION_DELIVERY_QUERY", "Invalid notification delivery filter or cursor"); }

    /** 已完成入口校验的投递筛选。 */
    public record Search(String channel, String status, int limit, Instant beforeTime, UUID beforeId, String context) {
        /** 只使用实际返回页的最后一条记录续查。 */
        public String cursor(NotificationDelivery value) { return encode(context + "\n" + value.createdAt() + "\n" + value.id()); }
    }
    /** 已完成入口校验的历史范围。 */
    public record History(int limit, Long beforeVersion, String context) {
        /** 历史只向更早版本翻页，不用页码推断总量。 */
        public String cursor(long version) { return encode(context + "\n" + version); }
    }
}
