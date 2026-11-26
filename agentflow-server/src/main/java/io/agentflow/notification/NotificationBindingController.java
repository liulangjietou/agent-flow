package io.agentflow.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.common.ErrorCode;
import io.agentflow.common.SystemRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 租户管理员维护员工动态通知收件绑定；底层 SMTP/企微通道与凭据仍只来自部署配置。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/notifications/bindings")
public class NotificationBindingController {
    private final CurrentActor actors;
    private final JdbcNotificationBindingRepository repository;
    private final NotificationDestinations destinations;
    private final IdempotencyExecutor idempotency;

    /** 复用统一会话认证、目的地格式校验与幂等执行器。 */
    public NotificationBindingController(CurrentActor actors, JdbcNotificationBindingRepository repository,
                                         NotificationDestinations destinations, IdempotencyExecutor idempotency) {
        this.actors = actors; this.repository = repository; this.destinations = destinations; this.idempotency = idempotency;
    }

    /** 列出本租户已部署的通知通道与已保存的员工动态绑定。 */
    @GetMapping
    public ResponseEntity<Catalog> list() {
        var actor = actors.actor();
        actor.requireRole(SystemRole.ADMIN);
        var items = repository.list(actor.tenantId()).stream().map(BindingView::from).toList();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Catalog(destinations.configuredServers(actor.tenantId()), items));
    }

    /** 新增或更新单个员工在指定渠道的收件地址，无需重启服务即可生效。 */
    @PutMapping
    public ResponseEntity<String> upsert(@Valid @RequestBody UpsertInput input, HttpServletRequest request) {
        var actor = actors.actor();
        actor.requireRole(SystemRole.ADMIN);
        var response = idempotency.execute(request, HttpStatus.OK, () -> {
            var candidate = new NotificationDeliveryConfiguration.Binding(actor.tenantId(), input.recipientId(),
                    input.channel(), input.serverId(), input.address(), input.enabled());
            try { destinations.validateDynamicBinding(input.id(), candidate); }
            catch (IllegalStateException invalid) {
                throw new DomainException(ErrorCode.INVALID_REQUEST, "Notification binding address, id or server is invalid");
            }
            var saved = repository.upsert(input.id(), actor.tenantId(), input.recipientId(), input.channel(),
                    input.serverId(), input.address(), input.enabled(), input.expectedVersion(), actor.userId(), Instant.now());
            return BindingView.from(saved);
        });
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header("Cache-Control", "no-store").body(response.getBody());
    }

    /** 删除本租户中指定的动态绑定记录；已入队的旧投递仍按冻结摘要校验。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, HttpServletRequest request) {
        var actor = actors.actor();
        actor.requireRole(SystemRole.ADMIN);
        var response = idempotency.execute(request, HttpStatus.OK, () -> {
            if (!repository.delete(actor.tenantId(), id)) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Notification binding not found");
            }
            return new DeletedView(id, true);
        });
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header("Cache-Control", "no-store").body(response.getBody());
    }

    /**
     * 租户通知通道与员工动态绑定列表。
     * @author owlzhangfq@gmail.com
     */
    public record Catalog(List<NotificationDestinations.ChannelServer> servers, List<BindingView> bindings) { }

    /**
     * 单条员工动态绑定视图。
     * @author owlzhangfq@gmail.com
     */
    public record BindingView(String id, String recipientId, NotificationChannel channel, String serverId,
                              String address, boolean enabled, long version, String updatedBy, Instant updatedAt) {
        static BindingView from(JdbcNotificationBindingRepository.Record value) {
            return new BindingView(value.id(), value.recipientId(), value.channel(), value.serverId(),
                    value.address(), value.enabled(), value.version(), value.updatedBy(), value.updatedAt());
        }
    }

    /**
     * 动态绑定删除结果。
     * @author owlzhangfq@gmail.com
     */
    public record DeletedView(String id, boolean deleted) { }

    /**
     * 新增或更新员工动态绑定请求体，禁止传入租户覆盖或未知字段。
     * @author owlzhangfq@gmail.com
     */
    public record UpsertInput(@NotBlank @Size(max = 64) String id,
                              @NotBlank @Size(max = 128) String recipientId,
                              @NotNull NotificationChannel channel,
                              @NotBlank @Size(max = 64) String serverId,
                              @NotBlank @Size(max = 254) String address,
                              @NotNull Boolean enabled,
                              @Min(0) Long expectedVersion) {
        /** 拒绝跨租户或未声明属性。 */
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unknown notification binding field"); }
    }
}
