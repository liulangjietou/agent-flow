package io.agentflow.approval;

import io.agentflow.approval.process.FlowableTaskEnhancementService;
import io.agentflow.approval.process.FlowableTaskFacade;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
    private final FlowableTaskEnhancementService enhancements;

    /** 兼容既有测试构造。 */
    public TaskController(FlowableTaskFacade facade, IdempotencyExecutor idempotency) {
        this(facade, idempotency, null);
    }

    /** 创建控制器。 */
    @Autowired
    public TaskController(FlowableTaskFacade facade, IdempotencyExecutor idempotency,
                          FlowableTaskEnhancementService enhancements) {
        this.facade = facade;
        this.idempotency = idempotency;
        this.enhancements = enhancements;
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

    /** 查询当前任务可退回的本轮已完成历史人工节点。 */
    @GetMapping("/{taskId}/returnable-nodes")
    public List<FlowableTaskEnhancementService.ReturnableNode> returnableNodes(@PathVariable String taskId) {
        return enhancements.returnableNodes(taskId);
    }

    /** 执行审批任务动作。 */
    @PostMapping("/{taskId}/actions")
    public ResponseEntity<String> action(@PathVariable String taskId, @Valid @RequestBody TaskActionRequest request,
                                         HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> facade.action(taskId, request.action(), request.comment(), request.targetUser(), request.expectedVersion(), request.proxyId()));
    }

    /** 退回至本轮指定的已完成历史人工节点。 */
    @PostMapping("/{taskId}/return-to-node")
    public ResponseEntity<String> returnToNode(@PathVariable String taskId,
                                               @Valid @RequestBody ReturnToNodeRequest request,
                                               HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> enhancements.returnToNode(taskId, request.targetNodeId(), request.comment(), request.expectedVersion(), request.proxyId()));
    }

    /** 在审批节点回填该节点配置为 EDITABLE 的普通表单字段。 */
    @PatchMapping("/{taskId}/fields")
    public ResponseEntity<String> updateFields(@PathVariable String taskId,
                                               @Valid @RequestBody UpdateTaskFieldsRequest request,
                                               HttpServletRequest httpRequest) {
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> enhancements.updateEditableFields(taskId, request.expectedVersion(), request.fields(), request.proxyId()));
    }

    /**
     * 任务动作请求。
     * @author owlzhangfq@gmail.com
     */
    public record TaskActionRequest(@NotBlank String action, String comment, String targetUser,
                                    @NotNull Long expectedVersion, UUID proxyId) { }

    /**
     * 退回指定历史节点请求。
     * @author owlzhangfq@gmail.com
     */
    public record ReturnToNodeRequest(@NotBlank @Size(max = 128) String targetNodeId,
                                      @NotBlank @Size(max = 2000) String comment,
                                      @NotNull Long expectedVersion, UUID proxyId) { }

    /**
     * 审批节点回填可编辑字段请求。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateTaskFieldsRequest(@NotNull Long expectedVersion,
                                          @NotNull Map<String, Object> fields, UUID proxyId) { }
}
