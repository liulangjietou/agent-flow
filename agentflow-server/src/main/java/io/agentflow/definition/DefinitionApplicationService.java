package io.agentflow.definition;

import io.agentflow.event.EventContractBindings;
import io.agentflow.common.DomainException;
import io.agentflow.common.Actor;
import io.agentflow.form.FormSchema;
import io.agentflow.notification.NotificationTexts;
import io.agentflow.servicetask.ServiceTaskBindings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;
import static io.agentflow.definition.DefinitionModels.Graph;

/**
 * 流程定义用例服务，编排校验、并发控制和草稿生命周期。
 * @author owlzhangfq@gmail.com
 */
@Service
public class DefinitionApplicationService {
    private final DefinitionDraftRepository repository;
    private final DefinitionDeploymentPort deploymentPort;
    private final DefinitionPublicationRepository publications;
    private final DefinitionAssigneeDirectory assignees;
    private final DefinitionReferenceInspector references;
    private final EventContractBindings eventContracts;
    private final SubprocessDeploymentBindings subprocesses;
    private final ServiceTaskBindings serviceTasks;
    private final DefinitionValidator validator = new DefinitionValidator();
    private final BranchCoverageAnalyzer coverage = new BranchCoverageAnalyzer();
    private final DefinitionSimulator simulator = new DefinitionSimulator();
    private final DefinitionDiffService differences = new DefinitionDiffService();

    /** 创建定义服务。 */
    public DefinitionApplicationService(DefinitionDraftRepository repository, DefinitionDeploymentPort deploymentPort,
                                        DefinitionPublicationRepository publications, DefinitionAssigneeDirectory assignees,
                                        DefinitionReferenceInspector references, EventContractBindings eventContracts,
                                        SubprocessDeploymentBindings subprocesses, ServiceTaskBindings serviceTasks) {
        this.repository = repository;
        this.deploymentPort = deploymentPort;
        this.publications = publications;
        this.assignees = assignees;
        this.references = references;
        this.eventContracts = eventContracts;
        this.subprocesses = subprocesses;
        this.serviceTasks = serviceTasks;
    }

    /** 校验流程图，不改变持久化状态。 */
    public List<String> validate(Graph graph) {
        return validate(graph, null);
    }

    /** 联合校验流程图与表单，不改变持久化状态。 */
    public List<String> validate(Graph graph, FormSchema formSchema) {
        return List.copyOf(validator.validate(graph, formSchema));
    }

    /** 结构通过后核对身份源、明确日历修订与事件契约；跨上下文读取由应用层编排。 */
    public List<String> validate(String tenantId, Graph graph, FormSchema formSchema) {
        List<String> errors = validate(graph, formSchema);
        if (!errors.isEmpty()) return errors;
        return references.inspect(tenantId, graph, formSchema);
    }

    /** 发布就绪检查；草稿存储和样例模拟仍只要求结构与类型合法。 */
    public Validation inspect(Graph graph, FormSchema formSchema) {
        return inspect(graph, formSchema, null);
    }

    /** 预检携带当前流程标识时，同图元素一起验证，不查询或修改流程定义。 */
    public Validation inspect(Graph graph, FormSchema formSchema, String processKey) {
        return inspectStructure(graph, formSchema, processKey);
    }

    private Validation inspectStructure(Graph graph, FormSchema formSchema, String processKey) {
        List<String> errors = validator.validate(graph, formSchema, processKey);
        if (!errors.isEmpty()) return new Validation(errors, List.of());
        var diagnostics = coverage.analyze(graph, formSchema);
        var routingErrors = diagnostics.stream().filter(issue -> issue.severity() == BranchCoverageAnalyzer.Severity.ERROR)
                .map(issue -> issue.code().name() + ":" + issue.gatewayId()).distinct().toList();
        return new Validation(routingErrors, diagnostics);
    }

    /** 发布检查在纯领域结果之外核对当前租户审批人、固定日历修订及事件契约。 */
    public Validation inspect(String tenantId, Graph graph, FormSchema formSchema) {
        return inspect(tenantId, graph, formSchema, null);
    }

    /** 先检查标识和图结构，再核对当前租户审批人、固定日历修订及事件契约，发布与设计预检共用。 */
    public Validation inspect(String tenantId, Graph graph, FormSchema formSchema, String processKey) {
        Validation result = inspectStructure(graph, formSchema, processKey);
        if (!result.errors().isEmpty()) return result;
        var errors = new java.util.ArrayList<>(references.inspect(tenantId, graph, formSchema));
        errors.addAll(subprocesses.inspect(tenantId, graph, formSchema));
        return new Validation(errors, result.branchDiagnostics());
    }

    /**
     * 发布就绪结果保持错误兼容字段，并独立提供提醒及精确反例。
     * @author owlzhangfq@gmail.com
     */
    public record Validation(List<String> errors, List<BranchCoverageAnalyzer.Diagnostic> branchDiagnostics) {
        public Validation { errors = List.copyOf(errors); branchDiagnostics = List.copyOf(branchDiagnostics); }
    }

    /** 读取当前租户设计器可用的审批规则，不改变流程或身份数据。 */
    public List<DefinitionAssigneeDirectory.Option> assigneeOptions(String tenantId) {
        return assignees.options(tenantId);
    }

    /** 设计者将本租户组织对象加入版本化表单选项，不能借此更改目录。 */
    public List<DefinitionAssigneeDirectory.FormOption> formAssigneeOptions(String tenantId) { return assignees.formOptions(tenantId); }

    /** 抄送名单不要求审批资格；发布时仍使用同一目录再次校验。 */
    public List<DefinitionAssigneeDirectory.Option> copyOptions(String tenantId) { return assignees.copyOptions(tenantId); }

    /** 新建流程草稿。 */
    @Transactional
    public DefinitionDraft create(String tenantId, String key, String name, Graph graph) {
        return create(tenantId, key, name, graph, null);
    }

    /** 新建携带表单契约的流程草稿。 */
    @Transactional
    public DefinitionDraft create(String tenantId, String key, String name, Graph graph, FormSchema formSchema) {
        return create(tenantId, key, name, graph, formSchema, null);
    }

    /** 保存与流程版本共同发布的站内通知配置。 */
    @Transactional
    public DefinitionDraft create(String tenantId, String key, String name, Graph graph, FormSchema formSchema,
                                  NotificationTexts notificationTexts) {
        requireValid(graph, formSchema, key);
        serviceTasks.requireDeclared(tenantId, graph, formSchema);
        DefinitionDraft draft = DefinitionDraft.create(UUID.randomUUID(), tenantId, key, name, graph, formSchema, notificationTexts);
        return repository.save(draft);
    }

    /** 更新草稿，发布后的定义不可修改。 */
    @Transactional
    public DefinitionDraft update(String tenantId, UUID id, String name, Graph graph, long expectedRevision) {
        return update(tenantId, id, name, graph, null, expectedRevision);
    }

    /** 使用同一个草稿 revision 原子更新表单和流程图。 */
    @Transactional
    public DefinitionDraft update(String tenantId, UUID id, String name, Graph graph, FormSchema formSchema, long expectedRevision) {
        return update(tenantId, id, name, graph, formSchema, null, expectedRevision);
    }

    /** 同一 revision 保护图、表单与通知配置，旧调用未传文案时保持原配置。 */
    @Transactional
    public DefinitionDraft update(String tenantId, UUID id, String name, Graph graph, FormSchema formSchema,
                                  NotificationTexts notificationTexts, long expectedRevision) {
        DefinitionDraft draft = get(tenantId, id);
        requireValid(graph, formSchema == null ? draft.formSchema() : formSchema, draft.key());
        serviceTasks.requireDeclared(tenantId, graph, formSchema == null ? draft.formSchema() : formSchema);
        draft.update(name, graph, formSchema, notificationTexts, expectedRevision);
        return repository.save(draft);
    }

    /** 分配业务版本并部署；仓储写入和引擎发布在同一事务内成功或回滚。 */
    @Transactional
    public DefinitionDraft publish(Actor publisher, UUID id, long expectedRevision, String changeNote) {
        DefinitionDraft draft = get(publisher.tenantId(), id);
        Validation validation = inspect(publisher.tenantId(), draft.graph(), draft.formSchema(), draft.key());
        if (!validation.errors().isEmpty()) throw new DefinitionValidationException(validation.errors());
        eventContracts.requireAvailable(publisher.tenantId(), draft.graph());
        long version = repository.nextVersion(publisher.tenantId(), draft.key());
        DefinitionPublication publication = DefinitionPublication.prepare(draft, version, publisher, changeNote, Instant.now(), validation.branchDiagnostics());
        draft.publish(expectedRevision, version);
        DefinitionDraft published = repository.save(draft);
        deploymentPort.deploy(published);
        publications.save(publication);
        return published;
    }

    /** 读取已发布版本的事实；缺失表示历史记录不完整，不能据此生成操作者或说明。 */
    public Optional<DefinitionPublication> publication(String tenantId, UUID id) {
        DefinitionDraft definition = get(tenantId, id);
        if (definition.status() == DefinitionModels.DraftStatus.DRAFT) {
            throw new DomainException("DEFINITION_NOT_PUBLISHED", "Definition has not been published");
        }
        return publications.findByDefinition(tenantId, id);
    }

    /** 使用同一套受限条件求值器模拟流程路径，不接触流程引擎。 */
    public List<String> simulate(String tenantId, UUID id, DefinitionModels.EvaluationContext context) {
        return simulate(tenantId, id, context, null);
    }

    /** 已保存版本同样要求明确的合成金额，不从现有业务中补造模拟依据。 */
    public List<String> simulate(String tenantId, UUID id, DefinitionModels.EvaluationContext context, io.agentflow.finance.Money splitRoutingAmount) {
        DefinitionDraft draft = get(tenantId, id);
        return simulatePreview(tenantId, draft.graph(), draft.formSchema(), context, splitRoutingAmount).path();
    }

    /** 试算当前设计快照，不读取或修改持久化草稿，也不启动实例。 */
    public DefinitionSimulator.Result simulatePreview(Graph graph, FormSchema formSchema, DefinitionModels.EvaluationContext context) {
        return simulator.simulateDetailed(graph, formSchema, context);
    }

    /** 公开模拟同时核对服务任务的原契约、字段权限和本次输入，整个过程不创建队列或访问远端。 */
    public DefinitionSimulator.Result simulatePreview(String tenantId, Graph graph, FormSchema formSchema, DefinitionModels.EvaluationContext context) {
        return simulatePreview(tenantId, graph, formSchema, context, null);
    }

    /** 合成金额只交给路由模拟，服务契约仍校验本单原始表单输入。 */
    public DefinitionSimulator.Result simulatePreview(String tenantId, Graph graph, FormSchema formSchema,
                                                       DefinitionModels.EvaluationContext context, io.agentflow.finance.Money splitRoutingAmount) {
        var result = simulator.simulateDetailed(graph, formSchema, context, splitRoutingAmount);
        serviceTasks.requireReady(tenantId, graph, formSchema, context.values());
        return result;
    }

    /** 只读比较同租户、同 key 的发布基线与当前设计，不要求当前图已经通过发布校验。 */
    public Comparison compare(String tenantId, UUID baselineId, String key, DefinitionDiffService.Snapshot current) {
        DefinitionDraft baseline = get(tenantId, baselineId);
        if (baseline.status() != DefinitionModels.DraftStatus.PUBLISHED) {
            throw new DomainException("COMPARISON_BASELINE_REQUIRED", "Comparison baseline must be a published definition");
        }
        if (!baseline.key().equals(key)) throw new DomainException("COMPARISON_KEY_MISMATCH", "Comparison requires the same process key");
        return new Comparison(new Baseline(baseline.id(), baseline.key(), baseline.name(), baseline.version()), differences.compare(
                new DefinitionDiffService.Snapshot(baseline.name(), baseline.graph(), baseline.formSchema(), baseline.notificationTexts()), current));
    }

    /**
     * 已授权的基线标识，不携带完整聚合或其他租户信息。
     * @author owlzhangfq@gmail.com
     */
    public record Baseline(UUID id, String key, String name, long version) { }

    /**
     * 服务端读取的可信基线及本次只读差异。
     * @author owlzhangfq@gmail.com
     */
    public record Comparison(Baseline baseline, List<DefinitionDiffService.Change> changes) { }

    /** 查询定义。 */
    public DefinitionDraft get(String tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Process definition not found"));
    }

    /** 查询定义列表。 */
    public List<DefinitionDraft> list(String tenantId, String status) {
        return repository.findAll(tenantId, status);
    }

    private void requireValid(Graph graph, FormSchema formSchema, String processKey) {
        var errors = validator.validate(graph, formSchema, processKey);
        if (!errors.isEmpty()) {
            throw new DefinitionValidationException(errors);
        }
    }

}
