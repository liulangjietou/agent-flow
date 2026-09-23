package io.agentflow.integration;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 投递检索的入口白名单，游标绑定账号、租户、角色与筛选。
 * @author owlzhangfq@gmail.com
 */
public record WebhookQueryParameters(String target, String status, UUID applicationId, int limit,
                                     Instant beforeTime, UUID beforeId, String context) {
    private static final Set<String> KEYS = Set.of("target", "status", "applicationId", "limit", "cursor");
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** 一次解析有界条件，不允许覆盖租户或排序。 */
    public static WebhookQueryParameters parse(Actor actor, Map<String, String> raw, JsonUtil json) {
        if (!KEYS.containsAll(raw.keySet())) throw invalid();
        try {
            String target = raw.getOrDefault("target", ""), status = raw.getOrDefault("status", "");
            if (!target.isEmpty() && !target.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw invalid();
            if (!status.isEmpty()) DeliveryProgress.Status.valueOf(status);
            UUID applicationId = raw.containsKey("applicationId") ? uuid(raw.get("applicationId")) : null;
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : 30;
            if (limit < 1 || limit > 100) throw invalid();
            String context = digest(json.write(List.of("webhooks-v1", actor.tenantId(), actor.userId(),
                    actor.roles().stream().sorted().toList(), target, status, applicationId == null ? "" : applicationId.toString())));
            Instant beforeTime = null; UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])) throw invalid();
                beforeTime = Instant.parse(parts[1]); beforeId = uuid(parts[2]);
                if (beforeTime.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || beforeTime.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) throw invalid();
            }
            return new WebhookQueryParameters(target, status, applicationId, limit, beforeTime, beforeId, context);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) { throw invalid(); }
    }

    /** 使用不可变事件时间和记录标识续查。 */
    public String cursor(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + time + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }
    private static UUID uuid(String value) { if (!value.matches(UUID_PATTERN)) throw invalid(); return UUID.fromString(value); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_WEBHOOK_QUERY", "Invalid webhook filter or cursor"); }
}
