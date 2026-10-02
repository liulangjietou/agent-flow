package io.agentflow.definition;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.api.idempotency.IdempotencyExecutor;
import io.agentflow.form.FormSchema;
import io.agentflow.form.FormFieldProjection;
import io.agentflow.notification.NotificationTexts;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final IdempotencyExecutor idempotency;
    private final DefinitionInitiatorRequirements initiatorRequirements;

    /** 创建控制器。 */
    public DefinitionController(DefinitionApplicationService service, CurrentActor currentActor, IdempotencyExecutor idempotency,
                                DefinitionInitiatorRequirements initiatorRequirements) {
        this.service = service;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
        this.initiatorRequirements = initiatorRequirements;
    }

    /** 校验设计器图，不落库。 */
    @PostMapping("/validate")
    public ValidationResponse validate(@Valid @RequestBody GraphRequest request) {
        Actor actor = currentActor.actor();
        // 普通用户仍可检查图结构，但不得通过校验接口探测身份目录中的账号。
        var validation = canManageDefinitions(actor)
                ? service.inspect(actor.tenantId(), request.graph(), request.formSchema(), request.key())
                : service.inspect(request.graph(), request.formSchema(), request.key());
        return new ValidationResponse(validation.errors(), validation.branchDiagnostics());
    }

    /** 只读地将旧条件转换为等价新版表达式，结果仍须由既有草稿用例保存。 */
    @PostMapping("/upgrade-conditions")
    public ResponseEntity<Graph> upgradeConditions(@Valid @RequestBody GraphRequest request) {
        requireProcessAdmin();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ConditionLanguageUpgrade().upgrade(request.graph()));
    }

    /** 仅流程管理员可读取当前租户的审批人配置目录。 */
    @GetMapping("/assignee-options")
    public ResponseEntity<List<DefinitionAssigneeDirectory.Option>> assigneeOptions() {
        requireProcessAdmin();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.assigneeOptions(currentActor.actor().tenantId()));
    }

    /** 表单选项目录只向本租户流程管理员开放，不返回认证主体或组织审计。 */
    @GetMapping("/form-assignee-options")
    public ResponseEntity<List<DefinitionAssigneeDirectory.FormOption>> formAssigneeOptions() {
        requireProcessAdmin();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.formAssigneeOptions(currentActor.actor().tenantId()));
    }

    /** 设计器的抄送名单只向流程管理员开放，不要求收件人具备审批资格。 */
    @GetMapping("/copy-options")
    public ResponseEntity<List<DefinitionAssigneeDirectory.Option>> copyOptions() {
        requireProcessAdmin();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.copyOptions(currentActor.actor().tenantId()));
    }

    /** 仅对设计者提供的测试内容计算权限展示，不读取申请或授予所选节点的实际权限。 */
    @PostMapping("/field-preview")
    public ResponseEntity<FormFieldProjection> fieldPreview(@Valid @RequestBody FieldPreviewRequest request) {
        requireProcessAdmin();
        request.formSchema().validateDraft(request.values());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(FormFieldProjection.forNodes(request.formSchema(), request.values(), request.nodeIds()));
    }

    /**
     * 预览只接受显式测试数据；空节点集合表示没有节点参与事实的管理视角。
     * @author owlzhangfq@gmail.com
     */
    public record FieldPreviewRequest(@NotNull FormSchema formSchema, @NotNull java.util.Map<String, Object> values,
                                      @NotNull @Size(max = FormSchema.MAX_PERMISSION_NODES)
                                      java.util.Set<@NotBlank @Size(max = FormSchema.MAX_NODE_ID_LENGTH) String> nodeIds) { }

    /** 创建流程草稿。 */
    @PostMapping
    public ResponseEntity<String> create(@Valid @RequestBody DefinitionRequest request, HttpServletRequest httpRequest) {
        requireProcessAdmin();
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> DefinitionResponse.from(service.create(currentActor.actor().tenantId(), request.key(), request.name(), request.graph(), request.formSchema(), request.notificationTexts())));
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

    /** 仅检查用户选中的原发布版本，不为目录每一行展开依赖，也不授予启动权限。 */
    @GetMapping("/{id}/initiator-requirements")
    public ResponseEntity<DefinitionInitiatorRequirements.View> initiatorRequirements(@PathVariable UUID id) {
        var definition = requireVisibleDefinition(id);
        if (definition.status() != DefinitionModels.DraftStatus.PUBLISHED) {
            throw new DomainException("NOT_FOUND", "Published process definition not found");
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new DefinitionInitiatorRequirements.View(definition.key(), definition.version(),
                        initiatorRequirements.required(definition.tenantId(), definition.graph())));
    }

    /** 更新草稿。 */
    @PutMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable UUID id, @Valid @RequestBody UpdateDefinitionRequest request,
                                         HttpServletRequest httpRequest) {
        requireProcessAdmin();
        return idempotency.execute(httpRequest, HttpStatus.OK, () -> DefinitionResponse.from(service.update(
                currentActor.actor().tenantId(), id, request.name(), request.graph(), request.formSchema(), request.notificationTexts(), request.expectedRevision())));
    }

    /** 发布流程定义。 */
    @PostMapping("/{id}/publish")
    public ResponseEntity<String> publish(@PathVariable UUID id, @RequestParam long expectedRevision,
                                          @RequestBody(required = false) PublicationRequest request, HttpServletRequest httpRequest) {
        requireProcessAdmin();
        // 旧版无正文的成功请求仍可回放；只有新的执行才进入领域层要求发布说明。
        return idempotency.execute(httpRequest, HttpStatus.OK,
                () -> DefinitionResponse.from(service.publish(currentActor.actor(), id, expectedRevision, request == null ? null : request.changeNote())));
    }

    /** 读取发布事实，只有流程管理员可以查看完整发布依据。 */
    @GetMapping("/{id}/publication")
    public PublicationResponse publication(@PathVariable UUID id) {
        requireProcessAdmin();
        return service.publication(currentActor.actor().tenantId(), id)
                .map(value -> new PublicationResponse(true, value)).orElseGet(() -> new PublicationResponse(false, null));
    }

    /**
     * 发布说明；操作者和权限只能取自认证上下文。
     * @author owlzhangfq@gmail.com
     */
    public record PublicationRequest(String changeNote) { }

    /**
     * 显式区分完整事实与历史缺失，避免用默认值冒充历史。
     * @author owlzhangfq@gmail.com
     */
    public record PublicationResponse(boolean recorded, @JsonInclude(JsonInclude.Include.ALWAYS) DefinitionPublication publication) { }

    /** 发布前模拟流程路径。 */
    @PostMapping("/simulate")
    public DefinitionSimulator.Result simulatePreview(@Valid @RequestBody PreviewSimulationRequest request) {
        requireProcessAdmin();
        return service.simulatePreview(request.graph(), request.formSchema(), new DefinitionModels.EvaluationContext(request.values()));
    }

    /** 模拟已保存定义，保留已有调用契约。 */
    @PostMapping("/{id}/simulate")
    public SimulationResponse simulate(@PathVariable UUID id, @RequestBody(required = false) SimulationRequest request) {
        DefinitionDraft draft = requireVisibleDefinition(id);
        var context = request == null || request.values() == null
                ? new DefinitionModels.EvaluationContext(java.util.Map.of())
                : new DefinitionModels.EvaluationContext(request.values());
        return new SimulationResponse(service.simulate(draft.tenantId(), id, context));
    }

    /** 将当前设计与同流程的发布版本比较，不写入草稿或部署引擎。 */
    @PostMapping("/{id}/compare")
    public DefinitionApplicationService.Comparison compare(@PathVariable UUID id, @Valid @RequestBody ComparisonRequest request) {
        requireProcessAdmin();
        return service.compare(currentActor.actor().tenantId(), id, request.key(),
                new DefinitionDiffService.Snapshot(request.name(), request.graph(), request.formSchema(), request.notificationTexts()));
    }

    /**
     * formSchema 表示完整快照；null 明确表示未绑定表单。
     * @author owlzhangfq@gmail.com
     */
    public record ComparisonRequest(@NotBlank String key, @NotBlank String name, @NotNull Graph graph, FormSchema formSchema, NotificationTexts notificationTexts) { }

    /**
     * 设计器请求图；可选 key 用于预检流程标识，旧图校验请求保持兼容。
     * @author owlzhangfq@gmail.com
     */
    public record GraphRequest(@NotNull Graph graph, FormSchema formSchema, String key) { }
    /**
     * 创建定义请求。
     * @author owlzhangfq@gmail.com
     */
    public record DefinitionRequest(@NotBlank String key, @NotBlank String name, @NotNull Graph graph, FormSchema formSchema, NotificationTexts notificationTexts) { }
    /**
     * 更新定义请求。
     * @author owlzhangfq@gmail.com
     */
    public record UpdateDefinitionRequest(@NotBlank String name, @NotNull Graph graph, long expectedRevision, FormSchema formSchema, NotificationTexts notificationTexts) { }
    /**
     * 校验结果。
     * @author owlzhangfq@gmail.com
     */
    public record ValidationResponse(List<String> errors, List<BranchCoverageAnalyzer.Diagnostic> branchDiagnostics) { }
    /**
     * 模拟输入。
     * @author owlzhangfq@gmail.com
     */
    public record SimulationRequest(java.util.Map<String, Object> values) { }
    /**
     * 当前设计与测试数据，不接受需要加载其他租户资源的定义标识。
     * @author owlzhangfq@gmail.com
     */
    public record PreviewSimulationRequest(@NotNull Graph graph, FormSchema formSchema,
                                           @NotNull java.util.Map<String, Object> values) { }
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
                                     String status, Graph graph, @JsonInclude(JsonInclude.Include.ALWAYS) FormSchema formSchema, NotificationTexts notificationTexts, boolean startEnabled) {
        /** 将定义聚合转换为统一响应，供模板复制等创建入口复用。 */
        public static DefinitionResponse from(DefinitionDraft draft) {
            return new DefinitionResponse(draft.id(), draft.tenantId(), draft.key(), draft.name(), draft.version(),
                    draft.revision(), draft.status().name(), draft.graph(), draft.formSchema(), draft.notificationTexts(), draft.startEnabled());
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
