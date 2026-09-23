package io.agentflow.notification;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/**
 * 当前登录人的站内消息接口，已读操作复用统一写请求恢复协议。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class InboxController {
    private final CurrentActor currentActor;
    private final InboxApplicationService service;
    private final IdempotencyExecutor idempotency;

    /** 注入会话、收件箱用例与幂等执行器。 */
    public InboxController(CurrentActor currentActor, InboxApplicationService service, IdempotencyExecutor idempotency) {
        this.currentActor = currentActor; this.service = service; this.idempotency = idempotency;
    }

    /** 按当前接收人查询，入口拒绝未知筛选。 */
    @GetMapping
    public InboxApplicationService.Page list(@RequestParam Map<String, String> parameters) {
        var actor = currentActor.actor();
        return service.list(actor, InboxQueryParameters.parse(actor, parameters));
    }

    /** 标为已读不触发审批，也不会扩大原申请权限。 */
    @PostMapping("/{id}/read")
    public ResponseEntity<String> read(@PathVariable UUID id, HttpServletRequest request) {
        return idempotency.execute(request, HttpStatus.OK, () -> service.read(currentActor.actor(), id));
    }
}
