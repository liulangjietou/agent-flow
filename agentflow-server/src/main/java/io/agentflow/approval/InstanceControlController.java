package io.agentflow.approval;

import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.approval.process.InstanceControlService;
import io.agentflow.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * 当前轮次的显式暂停、恢复与终止，原请求未知结果通过共享幂等机制恢复。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/runtime")
public class InstanceControlController {
    private final InstanceControlService instances;
    private final IdempotencyExecutor idempotency;

    /** 入口一次校验请求，服务负责锁后业务与引擎事实。 */
    public InstanceControlController(InstanceControlService instances, IdempotencyExecutor idempotency) { this.instances = instances; this.idempotency = idempotency; }

    /** 读取不授予运维权限，不缓存当前权限与运行状态。 */
    @GetMapping
    public ResponseEntity<InstanceControlService.View> read(@PathVariable UUID id, @PathVariable String roundNo, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(instances.read(id, round(roundNo, request)));
    }

    /** 暂停必须有具名原因和匹配的当前申请版本。 */
    @PostMapping("/pause")
    public ResponseEntity<String> pause(@PathVariable UUID id, @PathVariable String roundNo,
            @Valid @RequestBody InstanceControlService.Input input, HttpServletRequest request) {
        int number = round(roundNo, request); instances.requireAdministrator();
        return result(idempotency.execute(request, HttpStatus.OK, () -> instances.pause(id, number, input)));
    }

    /** 恢复原轮次，不接收新期限或覆盖已消耗的审批时长。 */
    @PostMapping("/resume")
    public ResponseEntity<String> resume(@PathVariable UUID id, @PathVariable String roundNo,
            @Valid @RequestBody InstanceControlService.Input input, HttpServletRequest request) {
        int number = round(roundNo, request); instances.requireAdministrator();
        return result(idempotency.execute(request, HttpStatus.OK, () -> instances.resume(id, number, input)));
    }

    /** 终止只针对当前在审根轮次；原回执重放仍要求当前管理员身份。 */
    @PostMapping("/terminate")
    public ResponseEntity<String> terminate(@PathVariable UUID id, @PathVariable String roundNo,
            @Valid @RequestBody InstanceControlService.Input input, HttpServletRequest request) {
        int number = round(roundNo, request); instances.requireAdministrator();
        return result(idempotency.execute(request, HttpStatus.OK, () -> instances.terminate(id, number, input)));
    }

    private ResponseEntity<String> result(ResponseEntity<String> value) {
        return ResponseEntity.status(value.getStatusCode()).headers(value.getHeaders()).cacheControl(CacheControl.noStore()).body(value.getBody());
    }
    private static int round(String value, HttpServletRequest request) {
        try {
            if (request.getQueryString() != null || !value.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) { throw new DomainException("INVALID_INSTANCE_QUERY", "A positive round number and no query parameters are required"); }
    }
}
