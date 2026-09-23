package io.agentflow.notification;

import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 入口一次校验收件箱筛选；游标不能混用不同租户、账号和已读条件。
 * @author owlzhangfq@gmail.com
 */
public record InboxQueryParameters(InboxRepository.Query query, String context) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;

    /** 拒绝调用方传入用户、租户或不支持的筛选。 */
    public static InboxQueryParameters parse(Actor actor, Map<String, String> raw) {
        if (!Set.of("read", "limit", "cursor").containsAll(raw.keySet())) throw invalid();
        try {
            String read = raw.getOrDefault("read", "all");
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            if (!Set.of("all", "unread").contains(read) || limit < 1 || limit > MAX_LIMIT) throw invalid();
            String context = UUID.nameUUIDFromBytes((actor.tenantId() + "\n" + actor.userId() + "\n" + read).getBytes(StandardCharsets.UTF_8)).toString();
            Instant beforeTime = null;
            UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])) throw invalid();
                beforeTime = Instant.parse(parts[1]); beforeId = UUID.fromString(parts[2]);
            }
            return new InboxQueryParameters(new InboxRepository.Query("unread".equals(read), limit, beforeTime, beforeId), context);
        } catch (IllegalArgumentException | DateTimeParseException exception) { throw invalid(); }
    }

    /** 同时间消息按唯一 ID 分页，避免跳过消息。 */
    public String cursor(InboxMessage message) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + message.createdAt() + "\n" + message.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static DomainException invalid() { return new DomainException("INVALID_INBOX_QUERY", "Invalid inbox filter or cursor"); }
}
