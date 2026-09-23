package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FormSchema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final DefinitionValidator validator = new DefinitionValidator();
    private final DefinitionSimulator simulator = new DefinitionSimulator();
    private final DefinitionDiffService differences = new DefinitionDiffService();

    /** 创建定义服务。 */
    public DefinitionApplicationService(DefinitionDraftRepository repository, DefinitionDeploymentPort deploymentPort) {
        this.repository = repository;
        this.deploymentPort = deploymentPort;
    }

    /** 校验流程图，不改变持久化状态。 */
    public List<String> validate(Graph graph) {
        return validator.validate(graph);
    }

    /** 联合校验流程图与表单，不改变持久化状态。 */
    public List<String> validate(Graph graph, FormSchema formSchema) {
        return validator.validate(graph, formSchema);
    }

    /** 新建流程草稿。 */
    @Transactional
    public DefinitionDraft create(String tenantId, String key, String name, Graph graph) {
        return create(tenantId, key, name, graph, null);
    }

    /** 新建携带表单契约的流程草稿。 */
    @Transactional
    public DefinitionDraft create(String tenantId, String key, String name, Graph graph, FormSchema formSchema) {
        requireValid(graph, formSchema);
        DefinitionDraft draft = DefinitionDraft.create(UUID.randomUUID(), tenantId, key, name, graph, formSchema);
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
        DefinitionDraft draft = get(tenantId, id);
        requireValid(graph, formSchema == null ? draft.formSchema() : formSchema);
        draft.update(name, graph, formSchema, expectedRevision);
        return repository.save(draft);
    }

    /** 分配业务版本并部署；仓储写入和引擎发布在同一事务内成功或回滚。 */
    @Transactional
    public DefinitionDraft publish(String tenantId, UUID id, long expectedRevision) {
        DefinitionDraft draft = get(tenantId, id);
        requireValid(draft.graph(), draft.formSchema());
        draft.publish(expectedRevision, repository.nextVersion(tenantId, draft.key()));
        DefinitionDraft published = repository.save(draft);
        deploymentPort.deploy(published);
        return published;
    }

    /** 使用同一套受限条件求值器模拟流程路径，不接触流程引擎。 */
    public List<String> simulate(String tenantId, UUID id, DefinitionModels.EvaluationContext context) {
        DefinitionDraft draft = get(tenantId, id);
        return simulatePreview(draft.graph(), draft.formSchema(), context).path();
    }

    /** 试算当前设计快照，不读取或修改持久化草稿，也不启动实例。 */
    public DefinitionSimulator.Result simulatePreview(Graph graph, FormSchema formSchema, DefinitionModels.EvaluationContext context) {
        return simulator.simulateDetailed(graph, formSchema, context);
    }

    /** 只读比较同租户、同 key 的发布基线与当前设计，不要求当前图已经通过发布校验。 */
    public Comparison compare(String tenantId, UUID baselineId, String key, DefinitionDiffService.Snapshot current) {
        DefinitionDraft baseline = get(tenantId, baselineId);
        if (baseline.status() != DefinitionModels.DraftStatus.PUBLISHED) {
            throw new DomainException("COMPARISON_BASELINE_REQUIRED", "Comparison baseline must be a published definition");
        }
        if (!baseline.key().equals(key)) throw new DomainException("COMPARISON_KEY_MISMATCH", "Comparison requires the same process key");
        return new Comparison(new Baseline(baseline.id(), baseline.key(), baseline.name(), baseline.version()), differences.compare(
                new DefinitionDiffService.Snapshot(baseline.name(), baseline.graph(), baseline.formSchema()), current));
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

    private void requireValid(Graph graph, FormSchema formSchema) {
        List<String> errors = validator.validate(graph, formSchema);
        if (!errors.isEmpty()) {
            throw new DefinitionValidationException(errors);
        }
    }
}
