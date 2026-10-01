package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 审计检索的入口白名单，游标绑定租户、账号、角色及已提交筛选。
 * @author owlzhangfq@gmail.com
 */
public record AuditSearchParameters(AuditSearchPort.Query query, String context) {
    private static final Set<String> KEYS = Set.of("q", "actor", "action", "source", "applicationId", "from", "to", "limit", "cursor");
    private static final Set<String> ACTIONS = Set.of("CREATE", "EXPENSE_REDUCE", "REVISE", "SUBMIT", "WITHDRAW", "CANCEL", "CLAIM", "RELEASE", "TRANSFER", "DELEGATE", "RESOLVE", "RETURN", "REJECT", "APPROVE", "ADD_SIGNER", "REMOVE_SIGNER", "TIMER_ELAPSED", "TIMER_FAILED", "TIMER_RETRY", "INSTANCE_PAUSE", "INSTANCE_RESUME", "INSTANCE_TERMINATE", "EVENT_RECEIVED", "SUBPROCESS_COMPLETED", "SUBPROCESS_STOPPED");
    private static final Set<String> SOURCES = Set.of("Application", "Task");
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999999Z");

    /** 统一解析操作日期、精确字段与有界游标，拒绝排序或租户覆盖。 */
    public static AuditSearchParameters parse(Actor actor, Map<String, String> raw, JsonUtil json) {
        if (!KEYS.containsAll(raw.keySet())) throw invalid();
        try {
            String text = text(raw, "q", 100), operator = text(raw, "actor", 128);
            String action = raw.getOrDefault("action", ""), source = raw.getOrDefault("source", "");
            if (!action.isEmpty() && !ACTIONS.contains(action) || !source.isEmpty() && !SOURCES.contains(source)) throw invalid();
            UUID applicationId = null;
            if (raw.containsKey("applicationId")) {
                if (!raw.get("applicationId").matches(UUID_PATTERN)) throw invalid();
                applicationId = UUID.fromString(raw.get("applicationId"));
            }
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            LocalDate from = date(raw, "from"), to = date(raw, "to");
            if (limit < 1 || limit > MAX_LIMIT
                    || from != null && to != null && from.isAfter(to)) throw invalid();
            String context = digest(json.write(List.of("audit-search-v1", actor.tenantId(), actor.userId(), actor.roles().stream().sorted().toList(),
                    text, operator, action, source, applicationId == null ? "" : applicationId.toString(),
                    from == null ? "" : from.toString(), to == null ? "" : to.toString())));
            Instant beforeTime = null; UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0]) || !parts[2].matches(UUID_PATTERN)) throw invalid();
                beforeTime = Instant.parse(parts[1]); beforeId = UUID.fromString(parts[2]);
                if (beforeTime.isBefore(EARLIEST) || beforeTime.isAfter(LATEST)) throw invalid();
            }
            return new AuditSearchParameters(new AuditSearchPort.Query(text, operator, action, source, applicationId,
                    from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC),
                    to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), limit, beforeTime, beforeId), context);
        } catch (IllegalArgumentException | DateTimeException exception) { throw invalid(); }
    }

    /** 审计时间不随申请修改改变，同时间通过事件记录标识稳定定位。 */
    public String cursor(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + time + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDate date(Map<String, String> raw, String name) {
        if (!raw.containsKey(name)) return null;
        String value = raw.get(name);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        LocalDate date = LocalDate.parse(value);
        if (date.getYear() < 1) throw invalid();
        return date;
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
    private static DomainException invalid() { return new DomainException("INVALID_AUDIT_QUERY", "Invalid audit filter or cursor"); }
}
