package io.agentflow.agent;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import org.springframework.util.MultiValueMap;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * 运行目录参数在入口校验，游标绑定实时身份和申请，不能充当读取授权。
 * @author owlzhangfq@gmail.com
 */
public record AssistRunQueryParameters(AssistRunReadPort.Query query, String context) {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    /** 拒绝未知参数、多值参数及越界游标，租户只从认证主体取得。 */
    public static AssistRunQueryParameters parse(Actor actor, UUID applicationId, MultiValueMap<String, String> raw) {
        if (!Set.of("roundNo", "limit", "cursor").containsAll(raw.keySet())
                || raw.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        try {
            Integer round = raw.containsKey("roundNo") ? Integer.valueOf(raw.getFirst("roundNo")) : null;
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.getFirst("limit")) : DEFAULT_LIMIT;
            if (round != null && round <= 0 || limit < 1 || limit > MAX_LIMIT) throw invalid();
            String binding = String.join("\n", "agent-runs-v1", actor.tenantId(), actor.userId(),
                    String.join(",", actor.roles().stream().sorted().toList()), applicationId.toString(),
                    round == null ? "" : round.toString());
            String context = UUID.nameUUIDFromBytes(binding.getBytes(StandardCharsets.UTF_8)).toString();
            Instant time = null;
            UUID id = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.getFirst("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])) throw invalid();
                time = Instant.parse(parts[1]);
                if (time.isBefore(MIN_TIME) || time.isAfter(MAX_TIME)) throw invalid();
                id = UUID.fromString(parts[2]);
                if (!id.toString().equalsIgnoreCase(parts[2])) throw invalid();
            }
            return new AssistRunQueryParameters(new AssistRunReadPort.Query(round, limit, time, id), context);
        } catch (IllegalArgumentException | DateTimeParseException exception) { throw invalid(); }
    }

    /** 游标时间来自列表 SQL 列，避免 JSON 纳秒与数据库精度不同导致漏项或重项。 */
    public String cursor(AssistRunReadPort.Item item) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (context + "\n" + item.createdAt() + "\n" + item.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static DomainException invalid() { return new DomainException("INVALID_AGENT_QUERY", "Invalid assist run filter or cursor"); }
}
