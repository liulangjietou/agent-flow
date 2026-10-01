package io.agentflow.approval;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.approval.process.CountersignMembershipService;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 会签增减使用独立协议，不复用批准动作；读写都不能用参数覆盖受控上下文。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}")
public class CountersignMembershipController {
    private final CountersignMembershipService service;
    private final IdempotencyExecutor idempotency;

    /** 应用服务承担审批业务，控制器只处理协议边界和成功回放。 */
    public CountersignMembershipController(CountersignMembershipService service, IdempotencyExecutor idempotency) {
        this.service = service; this.idempotency = idempotency;
    }

    /** 实时查询本节点的原快照、未决责任及可加人员。 */
    @GetMapping("/countersign-members")
    public ResponseEntity<CountersignMembershipService.View> read(@PathVariable String taskId, HttpServletRequest request) {
        requireNoQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(taskId));
    }

    /** 显式原因和版本随原请求固定，重放前仍要求当前有效审批资格。 */
    @PostMapping("/countersign-changes")
    public ResponseEntity<String> change(@PathVariable String taskId, @Valid @RequestBody CountersignMembershipService.Input input, HttpServletRequest request) {
        requireNoQuery(request);
        service.requireActor();
        var response = idempotency.execute(request, HttpStatus.OK, () -> service.change(taskId, input));
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new DomainException("INVALID_COUNTERSIGN_QUERY", "Countersign operations do not accept query parameters");
    }
}
