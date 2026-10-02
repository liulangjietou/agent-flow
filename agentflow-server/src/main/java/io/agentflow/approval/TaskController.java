package io.agentflow.approval;

import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 审批任务 REST 接口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final FlowableTaskFacade facade;
    private final IdempotencyExecutor idempotency;

    /** 创建控制器。 */
    public TaskController(FlowableTaskFacade facade, IdempotencyExecutor idempotency) {
        this.facade = facade;
        this.idempotency = idempotency;
    }

    /** 查询当前租户待办。 */
    @GetMapping
    public List<FlowableTaskFacade.TaskView> list(@RequestParam(defaultValue = "pending") String status) {
        return facade.list(status);
    }

    /** 获取当前可操作的单项任务，供列表与消息入口实时复核。 */
    @GetMapping("/{taskId}")
    public FlowableTaskFacade.TaskView get(@PathVariable String taskId) { return facade.get(taskId); }

    /** 查询当前任务可转交或委派的同租户有效接收人。 */
    @GetMapping("/{taskId}/recipients")
    public List<String> recipients(@PathVariable String taskId) { return facade.recipients(taskId); }

    /** 执行审批任务动作。 */
    @PostMapping("/{taskId}/actions")
    public ResponseEntity<String> action(@PathVariable String taskId, @Valid @RequestBody TaskActionRequest request,
                                         HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> facade.action(taskId, request.action(), request.comment(), request.targetUser(), request.expectedVersion(), request.proxyId()));
    }

    /**
     * 任务动作请求。
     * @author owlzhangfq@gmail.com
     */
    public record TaskActionRequest(@NotBlank String action, String comment, String targetUser,
                                    @NotNull Long expectedVersion, UUID proxyId) { }
}
