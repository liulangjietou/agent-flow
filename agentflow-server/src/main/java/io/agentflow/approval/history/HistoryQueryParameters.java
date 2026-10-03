package io.agentflow.approval.history;

import io.agentflow.common.DomainException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在 HTTP 边界一次校验筛选与不透明游标；分页只接受已授权申请的事件集合。
 * @author owlzhangfq@gmail.com
 */
public record HistoryQueryParameters(Instant from, Instant to, String action, Integer roundNo,
                                     int limit, boolean timeline, String context, Cursor cursor) {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> ACTIONS = Set.of("CREATE", "EXPENSE_REDUCE", "REVISE", "SUBMIT", "WITHDRAW", "CANCEL", "CLAIM", "RELEASE",
            "TRANSFER", "DELEGATE", "RESOLVE", "RETURN", "REJECT", "APPROVE", "ADD_SIGNER", "REMOVE_SIGNER",
            "TIMER_ELAPSED", "TIMER_FAILED", "TIMER_RETRY", "INSTANCE_PAUSE", "INSTANCE_RESUME", "INSTANCE_TERMINATE", "EVENT_RECEIVED", "SERVICE_TASK_COMPLETED", "SUBPROCESS_COMPLETED", "SUBPROCESS_STOPPED");

    /** 解析并拒绝非法或不支持的过滤字段；游标同时绑定申请、视图与筛选条件。 */
    public static HistoryQueryParameters parse(UUID applicationId, boolean timeline, Map<String, String> raw) {
        Set<String> allowed = timeline ? Set.of("roundNo", "limit", "cursor")
                : Set.of("from", "to", "action", "roundNo", "limit", "cursor");
        if (!allowed.containsAll(raw.keySet())) throw invalid();
        try {
            Instant from = raw.containsKey("from") ? Instant.parse(raw.get("from")) : null;
            Instant to = raw.containsKey("to") ? Instant.parse(raw.get("to")) : null;
            String action = raw.get("action");
            Integer round = raw.containsKey("roundNo") ? Integer.valueOf(raw.get("roundNo")) : null;
            int limit = raw.containsKey("limit") ? Integer.parseInt(raw.get("limit")) : DEFAULT_LIMIT;
            if (from != null && to != null && from.isAfter(to) || action != null && !ACTIONS.contains(action)
                    || round != null && round <= 0 || limit <= 0 || limit > MAX_LIMIT) throw invalid();
            String context = String.join("\n", "v1", applicationId.toString(), timeline ? "timeline" : "audit",
                    from == null ? "" : from.toString(), to == null ? "" : to.toString(), action == null ? "" : action,
                    round == null ? "" : round.toString());
            Cursor cursor = raw.containsKey("cursor") ? decode(raw.get("cursor"), context) : null;
            return new HistoryQueryParameters(from, to, action, round, limit, timeline, context, cursor);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    /** 在单申请范围合并后分页，时间相同的事件仍以完整来源标识定位，避免同毫秒丢行。 */
    public HistoryPage page(List<HistoryEvent> input) {
        Comparator<HistoryEvent> order = Comparator.comparing(HistoryEvent::occurredAt).thenComparing(HistoryQueryParameters::key);
        if (!timeline) order = order.reversed();
        List<HistoryEvent> filtered = input.stream()
                .filter(event -> from == null || !event.occurredAt().isBefore(from))
                .filter(event -> to == null || !event.occurredAt().isAfter(to))
                .filter(event -> action == null || action.equals(event.action()))
                .filter(event -> roundNo == null || roundNo.equals(event.roundNo())).sorted(order).toList();
        int start = 0;
        if (cursor != null) {
            int matched = -1;
            for (int i = 0; i < filtered.size(); i++) {
                HistoryEvent event = filtered.get(i);
                if (event.occurredAt().equals(cursor.occurredAt()) && key(event).equals(cursor.eventKey())) {
                    matched = i;
                    break;
                }
            }
            if (matched < 0) throw invalid();
            start = matched + 1;
        }
        int end = Math.min(start + limit, filtered.size());
        List<HistoryEvent> items = new ArrayList<>();
        for (int i = start; i < end; i++) items.add(filtered.get(i).sequenced(i + 1L));
        String next = end < filtered.size() ? encode(filtered.get(end - 1)) : null;
        return new HistoryPage(List.copyOf(items), next);
    }

    private String encode(HistoryEvent event) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (context + "\n" + event.occurredAt() + "\n" + key(event)).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String raw, String context) {
        if (raw.isEmpty() || raw.length() > 2048) throw invalid();
        String value = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        if (!value.startsWith(context + "\n")) throw invalid();
        String[] position = value.substring(context.length() + 1).split("\n", -1);
        if (position.length != 2 || position[1].isEmpty() || position[1].length() > 512) throw invalid();
        return new Cursor(Instant.parse(position[0]), position[1]);
    }

    private static String key(HistoryEvent event) {
        return event.source().name() + ":" + event.id();
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_HISTORY_QUERY", "Invalid history filter or cursor");
    }

    /**
     * 完整时间及来源标识共同构成游标位置，不是原始 SQL 字段或偏移量。
     * @author owlzhangfq@gmail.com
     */
    public record Cursor(Instant occurredAt, String eventKey) { }
}
