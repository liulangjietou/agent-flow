package io.agentflow.approval.operations;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.common.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计事件留存归档查询与受控触发接口（仅当前租户 ADMIN）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/operations/audit/retention")
public class AuditRetentionController {
    private final AuditRetentionService service;
    private final CurrentActor currentActor;
    private final IdempotencyExecutor idempotency;

    /** 构造审计留存控制器。 */
    public AuditRetentionController(AuditRetentionService service, CurrentActor currentActor, IdempotencyExecutor idempotency) {
        this.service = service;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
    }

    /** 查询当前租户留存归档配置、已归档数量与最近台账。 */
    @GetMapping
    public ResponseEntity<AuditRetentionService.RetentionStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.status(currentActor.actor()));
    }

    /** 手动触发一次当前租户超期审计分批归档。 */
    @PostMapping("/run")
    public ResponseEntity<String> run(@Valid @RequestBody(required = false) RunRetentionRequest request,
                                      HttpServletRequest http) {
        Integer retentionDays = request == null ? null : request.retentionDays();
        return idempotency.execute(http, HttpStatus.OK,
                () -> service.runForTenant(currentActor.actor(), retentionDays));
    }

    /**
     * 手动归档参数；留空则使用服务端默认保留天数（最少 30 天）。
     * @author owlzhangfq@gmail.com
     */
    public record RunRetentionRequest(Integer retentionDays) { }
}
