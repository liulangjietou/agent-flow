package io.agentflow.approval.workspace;

import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
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
import java.util.UUID;

/**
 * HTTP 入口一次校验工作台筛选，游标绑定账号、视图和条件。
 * @author owlzhangfq@gmail.com
 */
public record WorkspaceQueryParameters(WorkspaceReadPort.Query query, String context) {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> HANDLED_ACTIONS = Set.of("APPROVE", "RETURN", "REJECT", "TRANSFER", "DELEGATE", "RESOLVE");

    /** 拒绝越权过滤字段、非法范围及其他账号或查询条件下的游标。 */
    public static WorkspaceQueryParameters parse(Actor actor, boolean handled, Map<String, String> raw, JsonUtil json) {
        Set<String> allowed = handled ? Set.of("q", "action", "limit", "cursor") : Set.of("q", "view", "status", "limit", "cursor");
        if (!allowed.containsAll(raw.keySet())) throw invalid();
        try {
            String text = raw.getOrDefault("q", "").strip();
            String view = raw.getOrDefault("view", "started");
            String status = raw.getOrDefault("status", "");
            String action = raw.getOrDefault("action", "");
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            if (text.length() > 100 || text.chars().anyMatch(Character::isISOControl) || limit < 1 || limit > MAX_LIMIT
                    || !Set.of("started", "drafts").contains(view) || !action.isEmpty() && !HANDLED_ACTIONS.contains(action)) throw invalid();
            if (!status.isEmpty()) ApplicationStatus.valueOf(status);
            boolean drafts = "drafts".equals(view);
            if (drafts && !status.isEmpty() && !"DRAFT".equals(status)) throw invalid();
            String context = digest(json.write(List.of("v1", actor.tenantId(), actor.userId(), handled ? "handled" : view, text, status, action)));
            Instant beforeTime = null;
            UUID beforeId = null;
            if (raw.containsKey("cursor")) {
                String cursor = raw.get("cursor");
                if (cursor.isEmpty() || cursor.length() > 512) throw invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length != 3 || !context.equals(parts[0])) throw invalid();
                beforeTime = Instant.parse(parts[1]); beforeId = UUID.fromString(parts[2]);
            }
            return new WorkspaceQueryParameters(new WorkspaceReadPort.Query(drafts, text, status, action, limit, beforeTime, beforeId), context);
        } catch (IllegalArgumentException | DateTimeParseException exception) { throw invalid(); }
    }

    /** 时间与唯一行标识共同定位下一页，同时间记录不漏行。 */
    public String cursor(Instant time, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((context + "\n" + time + "\n" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static DomainException invalid() { return new DomainException("INVALID_WORKSPACE_QUERY", "Invalid workspace filter or cursor"); }
}
