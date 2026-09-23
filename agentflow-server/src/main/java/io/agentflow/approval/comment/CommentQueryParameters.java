package io.agentflow.approval.comment;

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
 * 评论分页入口统一校验，游标同时绑定租户、账号、角色、申请与轮次筛选。
 * @author owlzhangfq@gmail.com
 */
public record CommentQueryParameters(ApplicationCommentRepository.Query query, String context) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final Instant MIN_CURSOR_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_CURSOR_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    /** 只接受已支持的筛选，拒绝替换租户、作者或任意 SQL 参数。 */
    public static CommentQueryParameters parse(Actor actor, UUID applicationId, Map<String, String> raw) {
        if (!Set.of("roundNo", "limit", "cursor").containsAll(raw.keySet())) throw invalid();
        try {
            Integer roundNo = raw.containsKey("roundNo") ? Integer.valueOf(raw.get("roundNo")) : null;
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            if (roundNo != null && roundNo <= 0 || limit < 1 || limit > MAX_LIMIT) throw invalid();
            String binding = String.join("\n", "v1", actor.tenantId(), actor.userId(),
                    String.join(",", actor.roles().stream().sorted().toList()), applicationId.toString(),
                    roundNo == null ? "" : roundNo.toString());
            String context = UUID.nameUUIDFromBytes(binding.getBytes(StandardCharsets.UTF_8)).toString();
            Instant beforeTime = null;
            UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])) throw invalid();
                beforeTime = Instant.parse(parts[1]);
                // 编码可被客户端构造，越界时间在入口拒绝，避免 JDBC 转换溢出或数据库错误。
                if (beforeTime.isBefore(MIN_CURSOR_TIME) || beforeTime.isAfter(MAX_CURSOR_TIME)) throw invalid();
                beforeId = UUID.fromString(parts[2]);
                if (!beforeId.toString().equalsIgnoreCase(parts[2])) throw invalid();
            }
            return new CommentQueryParameters(new ApplicationCommentRepository.Query(roundNo, limit, beforeTime, beforeId), context);
        } catch (IllegalArgumentException | DateTimeParseException exception) { throw invalid(); }
    }

    /** 使用完整时间和唯一 ID，避免相同时间的评论在翻页时丢失。 */
    public String cursor(ApplicationComment comment) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (context + "\n" + comment.createdAt() + "\n" + comment.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_COMMENT_QUERY", "Invalid comment filter or cursor");
    }
}
