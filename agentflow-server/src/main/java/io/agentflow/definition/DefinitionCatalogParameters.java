package io.agentflow.definition;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 目录入口统一校验查询参数，并绑定租户、账号、权限及已提交筛选。
 * @author owlzhangfq@gmail.com
 */
public record DefinitionCatalogParameters(DefinitionCatalogPort.Query query, String context) {
    private static final Set<String> KEYS = Set.of("q", "status", "processKey", "version", "limit", "cursor");
    private static final Set<String> STATUSES = Set.of("", "DRAFT", "PUBLISHED");
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999999Z");

    /** 普通账号只能查询发布版本；不接受租户覆盖或自定义排序。 */
    public static DefinitionCatalogParameters parse(Actor actor, Map<String, String> raw, JsonUtil json) {
        if (!KEYS.containsAll(raw.keySet())) throw invalid();
        try {
            String text = text(raw, "q", 100), key = text(raw, "processKey", 128);
            String status = raw.getOrDefault("status", "");
            if (!STATUSES.contains(status)) throw invalid();
            boolean manager = actor.hasRole("ADMIN") || actor.hasRole("PROCESS_ADMIN");
            if (!manager && "DRAFT".equals(status)) throw new DomainException("FORBIDDEN", "Draft definitions are not visible to this role");
            if (!manager) status = "PUBLISHED";
            Long version = raw.containsKey("version") ? Long.valueOf(raw.get("version")) : null;
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            if (limit < 1 || limit > MAX_LIMIT || version != null && (version < 1 || version > Integer.MAX_VALUE
                    || key.isEmpty() || "DRAFT".equals(status))) throw invalid();
            String context = digest(json.write(List.of("definition-catalog-v1", actor.tenantId(), actor.userId(),
                    actor.roles().stream().sorted().toList(), text, status, key, version == null ? "" : version.toString())));
            Instant beforeTime = null; UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])
                        || !parts[2].matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
                beforeTime = Instant.parse(parts[1]); beforeId = UUID.fromString(parts[2]);
                if (beforeTime.isBefore(EARLIEST) || beforeTime.isAfter(LATEST)) throw invalid();
            }
            return new DefinitionCatalogParameters(new DefinitionCatalogPort.Query(text, status, key, version,
                    limit, beforeTime, beforeId), context);
        } catch (IllegalArgumentException | DateTimeException exception) { throw invalid(); }
    }

    /** 创建时间不随草稿保存或发布变化，同一时刻通过 UUID 保持稳定顺序。 */
    public String cursor(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + time + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Map<String, String> raw, String name, int max) {
        String value = raw.getOrDefault(name, "").strip();
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_DEFINITION_QUERY", "Invalid definition filter or cursor"); }
}
