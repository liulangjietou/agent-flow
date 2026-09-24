package io.agentflow.integration;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前租户管理员的投递运营入口，页面不能新增或覆盖外部地址与密钥。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
public class WebhookController {
    private final CurrentActor currentActor;
    private final WebhookTargets targets;
    private final JdbcWebhookStore store;
    private final JsonUtil json;
    private final IdempotencyExecutor idempotency;
    private final WebhookManagementService management;

    /** 注入权限上下文、投递存储及统一写请求幂等边界。 */
    public WebhookController(CurrentActor currentActor, WebhookTargets targets, JdbcWebhookStore store, JsonUtil json, IdempotencyExecutor idempotency, WebhookManagementService management) {
        this.currentActor = currentActor; this.targets = targets; this.store = store; this.json = json; this.idempotency = idempotency; this.management = management;
    }

    /** 返回部署方配置的目的地名称与启用状态。 */
    @GetMapping
    public ResponseEntity<List<WebhookTargets.TargetView>> targets() {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        return noStore(targets.views(actor.tenantId()));
    }

    /** 当前租户投递摘要有界分页，敏感事件体不进入查询响应。 */
    @GetMapping("/deliveries")
    public ResponseEntity<Page> deliveries(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var query = WebhookQueryParameters.parse(actor, raw, json);
        var rows = store.search(actor.tenantId(), query);
        int count = Math.min(rows.size(), query.limit());
        var last = count == 0 ? null : rows.get(count - 1);
        return noStore(new Page(List.copyOf(rows.subList(0, count)), rows.size() > count ? query.cursor(last.occurredAt(), last.id()) : null));
    }

    /** 按目的地和申请范围统计完整当前状态，不把分页或尝试次数当投递总量。 */
    @GetMapping("/overview")
    public ResponseEntity<JdbcWebhookStore.Overview> overview(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        return noStore(store.overview(actor.tenantId(), WebhookQueryParameters.parseOverview(actor, raw, json)));
    }

    /** 读取单条投递及有界尝试历史，跨租户标识返回 404。 */
    @GetMapping("/deliveries/{id}")
    public ResponseEntity<Detail> detail(@PathVariable UUID id) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var delivery = store.get(actor.tenantId(), id);
        return noStore(new Detail(delivery.summary(), store.attempts(id), store.retries(id)));
    }

    /** 人工重试只重新排队，持久化响应可使用原幂等键核验。 */
    @PostMapping("/deliveries/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @Valid @RequestBody RetryInput input, HttpServletRequest request) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var response = idempotency.execute(request, HttpStatus.OK, () -> management.retry(actor, id, input.expectedVersion()));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }
    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }

    /**
     * 投递分页，不暗示全库总数。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<JdbcWebhookStore.Summary> items, String nextCursor) { }
    /**
     * 尝试最多 50 条，人工请求最多 20 条，均从新到旧。
     * @author owlzhangfq@gmail.com
     */
    public record Detail(JdbcWebhookStore.Summary delivery, List<JdbcWebhookStore.Attempt> attempts, List<JdbcWebhookStore.RetryRequest> retryRequests) { }
    /**
     * 人工重试必须基于已读取的投递版本。
     * @author owlzhangfq@gmail.com
     */
    public record RetryInput(@NotNull @Min(1) Long expectedVersion) { }
}
