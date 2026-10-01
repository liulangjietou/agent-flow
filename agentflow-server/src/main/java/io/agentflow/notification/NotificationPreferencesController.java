package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 只从会话取得租户和接收人，外部地址、凭据与站内开关不接受请求覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationPreferencesController {
    private final CurrentActor actors;
    private final NotificationPreferencesService service;
    private final IdempotencyExecutor idempotency;

    /** 复用本人会话、设置用例和原请求恢复事务。 */
    public NotificationPreferencesController(CurrentActor actors, NotificationPreferencesService service, IdempotencyExecutor idempotency) {
        this.actors = actors; this.service = service; this.idempotency = idempotency;
    }

    /** 当前设置及默认值均不可缓存，不允许通过查询参数选取其他身份。 */
    @GetMapping
    public ResponseEntity<View> get(@RequestParam Map<String,String> parameters) {
        requireNoQuery(parameters);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(View.of(service.get(actors.actor())));
    }

    /** 同键回放原设置结果，不再次启用已经被后来操作关闭的渠道。 */
    @PutMapping
    public ResponseEntity<String> revise(@RequestParam Map<String,String> parameters, @Valid @RequestBody Input input, HttpServletRequest request) {
        requireNoQuery(parameters);
        var actor = actors.actor();
        var response = idempotency.execute(request, HttpStatus.OK,
                () -> View.of(service.revise(actor, input.expectedVersion(), input.emailEnabled(), input.enterpriseImEnabled())));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }

    private static void requireNoQuery(Map<String,String> parameters) {
        if (!parameters.isEmpty()) throw new DomainException("INVALID_NOTIFICATION_PREFERENCES", "Notification preferences do not accept query parameters");
    }

    /** 站内业务提醒始终开启；更新时间为 null 表示未保存过个人外部设置。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(boolean inAppEnabled, boolean emailEnabled, boolean enterpriseImEnabled, long version, Instant updatedAt) {
        static View of(NotificationPreferences value) { return new View(true, value.emailEnabled(), value.enterpriseImEnabled(), value.version(), value.updatedAt()); }
    }

    /** 所有外部开关显式提供，不能把遗漏误当关闭，也不能指定外部接收地址。
     * @author owlzhangfq@gmail.com
     */
    public record Input(@NotNull Boolean emailEnabled, @NotNull Boolean enterpriseImEnabled, @NotNull @PositiveOrZero Long expectedVersion) {
        /** 不接受身份、站内开关、邮件地址或其他额外字段。 */
        @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("Unknown notification preference field"); }
    }
}
