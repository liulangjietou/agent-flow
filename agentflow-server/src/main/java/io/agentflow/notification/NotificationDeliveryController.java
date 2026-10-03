package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static io.agentflow.notification.NotificationDeliveryViews.*;

/** 本人通知外发的查询与明确恢复，不接受接收人、地址或服务器覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/notifications/deliveries")
public class NotificationDeliveryController {
    private final CurrentActor actors;
    private final NotificationDeliveryQueries queries;
    private final NotificationDeliveryService deliveries;
    private final IdempotencyExecutor idempotency;
    private final JsonUtil json;

    /** 复用会话身份、只读投影和原请求幂等事务。 */
    public NotificationDeliveryController(CurrentActor actors, NotificationDeliveryQueries queries, NotificationDeliveryService deliveries,
                                          IdempotencyExecutor idempotency, JsonUtil json) {
        this.actors = actors; this.queries = queries; this.deliveries = deliveries; this.idempotency = idempotency; this.json = json;
    }

    /** 渠道与状态筛选均限于当前本人，未知及重复参数在入口拒绝。 */
    @GetMapping
    public ResponseEntity<Page> list(@RequestParam MultiValueMap<String,String> parameters) {
        var actor = actors.actor();
        return noStore(queries.list(actor, NotificationDeliveryQueryParameters.search(actor, parameters, json)));
    }

    /** 详情只有投递元数据和本人历史，当前恢复资格不等于写入授权。 */
    @GetMapping("/{id}")
    public ResponseEntity<Detail> detail(@PathVariable UUID id, @RequestParam MultiValueMap<String,String> parameters) {
        NotificationDeliveryQueryParameters.noQuery(parameters); var actor = actors.actor();
        return noStore(queries.detail(actor, id, NotificationDeliveryQueryParameters.history(actor, id, new LinkedMultiValueMap<>(), json)));
    }

    /** 历史独立分页，不用截断数组冒充完整历史。 */
    @GetMapping("/{id}/history")
    public ResponseEntity<HistoryPage> history(@PathVariable UUID id, @RequestParam MultiValueMap<String,String> parameters) {
        var actor = actors.actor();
        return noStore(queries.history(actor, id, NotificationDeliveryQueryParameters.history(actor, id, parameters, json)));
    }

    /** 原键回放只确认原排队结果，已经发送或后来关闭偏好也不重新执行。 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id, @RequestParam MultiValueMap<String,String> parameters,
                                        @Valid @RequestBody RetryInput input, HttpServletRequest request) {
        NotificationDeliveryQueryParameters.noQuery(parameters); var actor = actors.actor(); queries.requireOwner(actor, id);
        var response = idempotency.execute(request, HttpStatus.OK, () -> Summary.of(deliveries.retry(actor, id, input.expectedVersion(),
                input.acknowledgePossibleDuplicate(), input.reason(), Instant.now())));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }

    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(value); }

    /** 所有恢复选择显式提供；原因记录到原投递历史，不允许覆盖目标或状态。
     * @author owlzhangfq@gmail.com
     */
    public record RetryInput(@NotNull @Min(1) Long expectedVersion, @NotNull Boolean acknowledgePossibleDuplicate,
                             @NotBlank @Size(max = 1000) String reason) {
        /** 拒绝地址、收件人、状态及其他未定义字段。 */
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unknown notification retry field"); }
    }
}
