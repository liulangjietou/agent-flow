package io.agentflow.definition;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 流程管理员的版本治理入口，身份与租户始终来自认证上下文。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-definitions/{id}")
public class DefinitionAvailabilityController {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private final CurrentActor actors;
    private final DefinitionAvailabilityService service;
    private final IdempotencyExecutor idempotency;

    /** 复用原写请求事务和幂等机制。 */
    public DefinitionAvailabilityController(CurrentActor actors, DefinitionAvailabilityService service, IdempotencyExecutor idempotency) {
        this.actors = actors; this.service = service; this.idempotency = idempotency;
    }

    /** 停用或恢复指定发布版本，同一请求重放不追加重复记录。 */
    @PostMapping("/availability")
    public ResponseEntity<String> change(@PathVariable UUID id, @Valid @RequestBody ChangeRequest body, HttpServletRequest request) {
        requireAdministrator();
        return idempotency.execute(request, HttpStatus.OK, () -> DefinitionController.DefinitionResponse.from(
                service.change(actors.actor(), id, body.expectedRevision(), body.startEnabled(), body.reason())));
    }

    /** 操作说明仅向流程管理员开放，历史分页不接收租户或操作者覆盖。 */
    @GetMapping("/availability-history")
    public ResponseEntity<HistoryPage> history(@PathVariable UUID id, @RequestParam Map<String, String> raw) {
        requireAdministrator();
        long before; int limit;
        try {
            if (!Set.of("beforeRevision", "limit").containsAll(raw.keySet())) throw new IllegalArgumentException();
            before = Long.parseLong(raw.getOrDefault("beforeRevision", Long.toString(Long.MAX_VALUE)));
            limit = Integer.parseInt(raw.getOrDefault("limit", Integer.toString(DEFAULT_LIMIT)));
            if (before < 1 || limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new DomainException("INVALID_AVAILABILITY_QUERY", "Invalid availability history query");
        }
        var rows = service.history(actors.actor().tenantId(), id, before, limit);
        int count = Math.min(rows.size(), limit);
        Long next = rows.size() > count ? rows.get(count - 1).revision() : null;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new HistoryPage(List.copyOf(rows.subList(0, count)), next));
    }

    private void requireAdministrator() {
        var actor = actors.actor();
        if (!actor.hasRole("ADMIN") && !actor.hasRole("PROCESS_ADMIN")) {
            throw new DomainException("FORBIDDEN", "Process administrator role is required");
        }
    }

    /**
     * 操作意图不包含可伪造的身份字段。
     * @author owlzhangfq@gmail.com
     */
    public record ChangeRequest(@NotNull Boolean startEnabled, @PositiveOrZero long expectedRevision, String reason) { }

    /**
     * 下一页修订号只用于该版本的严格降序读取。
     * @author owlzhangfq@gmail.com
     */
    public record HistoryPage(List<DefinitionAvailabilityChange> items, Long nextBeforeRevision) { }
}
