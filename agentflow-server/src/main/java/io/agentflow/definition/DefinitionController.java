package io.agentflow.definition;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;
import static io.agentflow.definition.DefinitionModels.Graph;

/**
 * 流程设计器 REST 接口，草稿和发布版本均按租户隔离。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/process-definitions")
public class DefinitionController {
    private static final String PROCESS_ADMIN_ROLE = "PROCESS_ADMIN";
    private static final String ADMIN_ROLE = "ADMIN";
    private final DefinitionApplicationService service;
    private final CurrentActor currentActor;

    /** 创建控制器。 */
    public DefinitionController(DefinitionApplicationService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    /** 校验设计器图，不落库。 */
    @PostMapping("/validate")
    public ValidationResponse validate(@Valid @RequestBody GraphRequest request) {
        return new ValidationResponse(service.validate(request.graph()));
    }

    /** 创建流程草稿。 */
    @PostMapping
    public DefinitionResponse create(@Valid @RequestBody DefinitionRequest request) {
        requireProcessAdmin();
        return DefinitionResponse.from(service.create(currentActor.actor().tenantId(), request.key(), request.name(), request.graph()));
    }

    /** 查询租户流程定义。 */
    @GetMapping
    public List<DefinitionResponse> list(@RequestParam(required = false) String status) {
        Actor actor = currentActor.actor();
        if (!canManageDefinitions(actor) && status != null && !status.isBlank()
                && !"PUBLISHED".equalsIgnoreCase(status)) {
            throw new DomainException("FORBIDDEN", "Only published definitions are visible to this role");
        }
        String visibleStatus = canManageDefinitions(actor) ? status : "PUBLISHED";
        return service.list(actor.tenantId(), visibleStatus).stream().map(DefinitionResponse::from).toList();
    }

    /** 查询单个流程定义。 */
    @GetMapping("/{id}")
    public DefinitionResponse get(@PathVariable UUID id) {
        return DefinitionResponse.from(requireVisibleDefinition(id));
    }

    /** 更新草稿。 */
    @PutMapping("/{id}")
    public DefinitionResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDefinitionRequest request) {
        requireProcessAdmin();
        return DefinitionResponse.from(service.update(currentActor.actor().tenantId(), id, request.name(), request.graph(), request.expectedRevision()));
    }

    /** 发布流程定义。 */
    @PostMapping("/{id}/publish")
    public DefinitionResponse publish(@PathVariable UUID id, @RequestParam long expectedRevision) {
        requireProcessAdmin();
        return DefinitionResponse.from(service.publish(currentActor.actor().tenantId(), id, expectedRevision));
    }

    /** 发布前模拟流程路径。 */
    @PostMapping("/{id}/simulate")
    public SimulationResponse simulate(@PathVariable UUID id, @RequestBody(required = false) SimulationRequest request) {
        DefinitionDraft draft = requireVisibleDefinition(id);
        var context = request == null || request.values() == null
                ? new DefinitionModels.EvaluationContext(java.util.Map.of())
                : new DefinitionModels.EvaluationContext(request.values());
        return new SimulationResponse(service.simulate(draft.tenantId(), id, context));
    }

    /**
     * 设计器请求图。
     * @author owlzhangfq@gmail.com
     */
    public record GraphRequest(@NotNull Graph graph) { }
    /**
     * 创建定义请求。
     * @author owlzhangfq@gmail.com
     */
    public record DefinitionRequest(@NotBlank String key, @NotBlank String name, @NotNull Graph graph) { }
    /**
     * 更新定义请求。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateDefinitionRequest(@NotBlank String name, @NotNull Graph graph, long expectedRevision) { }
    /**
     * 校验结果。
     * @author owlzhangfq@gmail.com
     */
    public record ValidationResponse(List<String> errors) { }
    /**
     * 模拟输入。
     * @author owlzhangfq@gmail.com
     */
    public record SimulationRequest(java.util.Map<String, Object> values) { }
    /**
     * 模拟输出。
     * @author owlzhangfq@gmail.com
     */
    public record SimulationResponse(List<String> path) { }
    /**
     * 定义返回模型。
     * @author owlzhangfq@gmail.com
     */
    public record DefinitionResponse(UUID id, String tenantId, String key, String name, long version, long revision,
                                     String status, Graph graph) {
        static DefinitionResponse from(DefinitionDraft draft) {
            return new DefinitionResponse(draft.id(), draft.tenantId(), draft.key(), draft.name(), draft.version(),
                    draft.revision(), draft.status().name(), draft.graph());
        }
    }

    private DefinitionDraft requireVisibleDefinition(UUID id) {
        Actor actor = currentActor.actor();
        DefinitionDraft draft = service.get(actor.tenantId(), id);
        if (draft.status() != DefinitionModels.DraftStatus.PUBLISHED && !canManageDefinitions(actor)) {
            throw new DomainException("NOT_FOUND", "Process definition not found");
        }
        return draft;
    }

    private void requireProcessAdmin() {
        Actor actor = currentActor.actor();
        if (!canManageDefinitions(actor)) {
            throw new DomainException("FORBIDDEN", "Process administrator role is required");
        }
    }

    private boolean canManageDefinitions(Actor actor) {
        return actor.hasRole(PROCESS_ADMIN_ROLE) || actor.hasRole(ADMIN_ROLE);
    }
}
