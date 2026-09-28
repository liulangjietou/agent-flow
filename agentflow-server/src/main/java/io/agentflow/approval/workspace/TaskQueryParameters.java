package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.form.FormSchema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 待办查询入口白名单；游标绑定主体、角色和完整筛选条件。
 * @author owlzhangfq@gmail.com
 */
public record TaskQueryParameters(PendingTaskReadPort.Query query, String context) {
    private static final Set<String> KEYS = Set.of("q", "processKey", "applicant", "organization", "assignment", "deadline", "minAmount", "maxAmount", "limit", "cursor");
    private static final Set<String> ASSIGNMENTS = Set.of("all", "assigned", "unclaimed", "delegated");
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 一次解析筛选，不接受客户端覆盖租户、处理人和排序字段。 */
    public static TaskQueryParameters parse(Actor actor, Map<String, String> raw, JsonUtil json, Instant now) {
        if (!KEYS.containsAll(raw.keySet())) throw invalid();
        try {
            String text = text(raw, "q", 100), process = text(raw, "processKey", 128), applicant = text(raw, "applicant", 128);
            String organization = text(raw, "organization", 128);
            String assignment = raw.getOrDefault("assignment", "all");
            var deadline = switch (raw.getOrDefault("deadline", "all")) {
                case "all" -> PendingTaskReadPort.DeadlineFilter.ALL;
                case "overdue" -> PendingTaskReadPort.DeadlineFilter.OVERDUE;
                case "pending" -> PendingTaskReadPort.DeadlineFilter.PENDING;
                case "unrecorded" -> PendingTaskReadPort.DeadlineFilter.UNRECORDED;
                default -> throw invalid();
            };
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            BigDecimal min = number(raw, "minAmount"), max = number(raw, "maxAmount");
            if (!ASSIGNMENTS.contains(assignment) || limit < 1 || limit > MAX_LIMIT || min != null && max != null && min.compareTo(max) > 0) throw invalid();
            String context = digest(json.write(List.of("v1", actor.tenantId(), actor.userId(), actor.roles().stream().sorted().toList(),
                    text, process, applicant, assignment, min == null ? "" : min.toPlainString(), max == null ? "" : max.toPlainString())));
            // 不限制期限时保留原游标上下文；新增筛选不能复用其他期限条件的游标。
            if (deadline != PendingTaskReadPort.DeadlineFilter.ALL) context = digest(json.write(List.of("deadline-v1", context, deadline.name())));
            // 未设置组织条件时兼容已有游标；历史名称匹配不读取当前组织目录。
            if (!organization.isEmpty()) context = digest(json.write(List.of("organization-v1", context, organization)));
            Instant time = null; String id = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 768) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0]) || !parts[2].matches("[a-zA-Z0-9_-]{1,128}")) throw invalid();
                time = Instant.parse(parts[1]); id = parts[2];
                if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z")) || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) throw invalid();
            }
            return new TaskQueryParameters(new PendingTaskReadPort.Query(text, process, applicant, organization, assignment, deadline, now, min, max, limit, time, id), context);
        } catch (IllegalArgumentException | DateTimeParseException exception) { throw invalid(); }
    }

    /** 同时间任务通过唯一标识继续分页，不使用会随任务完成而漂移的 offset。 */
    public String cursor(Instant time, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + time + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Map<String, String> raw, String key, int length) {
        String value = raw.getOrDefault(key, "").strip();
        if (value.length() > length || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static BigDecimal number(Map<String, String> raw, String key) {
        return raw.containsKey(key) ? FormSchema.decimal(raw.get(key)).stripTrailingZeros() : null;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_TASK_QUERY", "Invalid pending task filter or cursor"); }
}
