package io.agentflow.approval;

import io.agentflow.approval.process.FlowableTaskFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审批任务 REST 接口。 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final FlowableTaskFacade facade;

    /** 创建控制器。 */
    public TaskController(FlowableTaskFacade facade) {
        this.facade = facade;
    }

    /** 查询当前租户待办。 */
    @GetMapping
    public List<FlowableTaskFacade.TaskView> list(@RequestParam(defaultValue = "pending") String status) {
        return facade.list(status);
    }

    /** 执行审批任务动作。 */
    @PostMapping("/{taskId}/actions")
    public FlowableTaskFacade.ActionResult action(@PathVariable String taskId,
                                                   @Valid @RequestBody TaskActionRequest request) {
        return facade.action(taskId, request.action(), request.comment(), request.targetUser(), request.expectedVersion());
    }

    /** 任务动作请求。 */
    public record TaskActionRequest(@NotBlank String action, String comment, String targetUser,
                                    @NotNull Long expectedVersion) { }
}
