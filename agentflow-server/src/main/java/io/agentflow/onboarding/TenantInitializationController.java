package io.agentflow.onboarding;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已认证租户管理员的首次配置入口，授权先于成功回放，不提供注册账号或授予权限的后门。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/system/initialization")
public class TenantInitializationController {
    private final CurrentActor actors;
    private final TenantInitializationService service;
    private final IdempotencyExecutor idempotency;

    /** 复用可信认证与写请求原键恢复协议。 */
    public TenantInitializationController(CurrentActor actors, TenantInitializationService service, IdempotencyExecutor idempotency) {
        this.actors = actors; this.service = service; this.idempotency = idempotency;
    }

    /** 不带写入副作用地读取本人租户的初始化事实与可选渠道。 */
    @GetMapping
    public ResponseEntity<TenantInitializationService.State> state(@RequestParam Map<String, String> query) {
        var actor = administrator(query);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.state(actor));
    }

    /** 成功响应、真实配置和审计原子保存，响应丢失可用相同请求恢复。 */
    @PostMapping
    public ResponseEntity<String> initialize(@RequestParam Map<String, String> query, @RequestBody InitializationRequest input,
                                              HttpServletRequest request) {
        var actor = administrator(query);
        var response = idempotency.execute(request, HttpStatus.CREATED, () -> service.initialize(actor, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).header("Cache-Control", "no-store").body(response.getBody());
    }

    private Actor administrator(Map<String, String> query) {
        var actor = actors.actor(); actor.requireRole("ADMIN");
        if (!query.isEmpty()) throw new DomainException("INVALID_INITIALIZATION_QUERY", "Initialization does not accept query parameters");
        return actor;
    }
}
