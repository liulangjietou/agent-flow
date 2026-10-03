package io.agentflow.servicetask;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 查询编排复用申请详情授权，投影运行事实时不返回原参数、目标、凭据或任意回执正文。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskRuntimeService {
    private final ApprovalApplicationFacade applications;
    private final SubmissionRoundRepository rounds;
    private final JdbcServiceTaskOperationRepository operations;
    private final ServiceTaskDefinitionSnapshots definitions;
    private final ServiceTaskCatalog catalog;

    /** 状态展示读取原定义和原契约，不用当前同名版本替代历史名称。 */
    public ServiceTaskRuntimeService(ApprovalApplicationFacade applications, SubmissionRoundRepository rounds,
            JdbcServiceTaskOperationRepository operations, ServiceTaskDefinitionSnapshots definitions, ServiceTaskCatalog catalog) {
        this.applications = applications;
        this.rounds = rounds;
        this.operations = operations;
        this.definitions = definitions;
        this.catalog = catalog;
    }

    /** 同一响应中的申请、轮次和操作使用一致快照；读取不领取、不续租，也不调用远端。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View read(UUID id, ServiceTaskRuntimeQuery query) {
        var application = applications.get(id);
        String tenant = application.tenantId();
        var round = rounds.findByRound(tenant, id, query.roundNo())
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round not found"));
        ServiceTaskOperation cursor = null;
        if (query.afterId() != null) {
            cursor = operations.find(tenant, query.afterId()).orElseThrow(ServiceTaskRuntimeQuery::invalid).operation();
            var binding = cursor.input().command().binding();
            if (!binding.applicationId().equals(id) || binding.roundNo() != query.roundNo()) throw ServiceTaskRuntimeQuery.invalid();
        }
        var page = operations.forRound(tenant, id, query.roundNo(), cursor, query.limit() + 1);
        if (page.isEmpty()) return new View(id, application.version(), query.roundNo(), round.status(), List.of(), null);
        var definition = definitions.find(tenant, application.processKey(), round.definitionVersion());
        var items = page.stream().limit(query.limit()).map(stored -> entry(stored, round, definition)).toList();
        UUID next = page.size() > query.limit() ? items.get(items.size() - 1).id() : null;
        return new View(id, application.version(), query.roundNo(), round.status(), items, next);
    }

    private Entry entry(JdbcServiceTaskOperationRepository.Stored stored, SubmissionRound round,
            ServiceTaskDefinitionSnapshots.Snapshot definition) {
        var operation = stored.operation();
        var command = operation.input().command();
        var binding = command.binding();
        var node = definition.graph().node(binding.nodeId());
        if (!round.processInstanceId().equals(binding.processInstanceId()) || round.definitionVersion() != binding.definitionVersion()
                || !definition.key().equals(binding.processKey()) || !definition.digest().equals(binding.definitionDigest())
                || node == null || node.type() != DefinitionModels.NodeType.SERVICE_TASK) {
            throw new DomainException("SERVICE_TASK_DEFINITION_MISMATCH", "Service task record must match its original submission round");
        }
        boolean available = catalog.available(command.tenantId(), new ServiceTaskCatalog.Installed(command.contract(), operation.input().targetDigest()));
        Instant completedAt = operation.observation() == null ? null : operation.observation().completedAt();
        return new Entry(command.id(), binding.nodeId(), node.name(), command.contract().key(), Long.toString(command.contract().version()),
                command.contract().name(), Long.toString(operation.version()), operation.status(), stored.progress(), operation.attempts(),
                operation.createdAt(), operation.updatedAt(), completedAt, stored.progressedAt(), operation.failure(), available);
    }

    /**
     * 原轮次状态与当前申请版本分别返回，不能将旧轮次结果当成新轮次推进。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID applicationId, long applicationVersion, int roundNo, SubmissionRound.Status roundStatus,
                       List<Entry> items, UUID nextAfterId) { }

    /**
     * 只列出有界状态字段；即使管理员读取也不会序列化命令和回执原文。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID id, String nodeId, String nodeName, String operationKey, String operationVersion,
                        String operationName, String version, ServiceTaskOperation.Status status,
                        JdbcServiceTaskOperationRepository.Progress progress, int attempts, Instant createdAt, Instant updatedAt,
                        Instant completedAt, Instant progressedAt, ServiceTaskOperation.Failure failure, boolean configurationAvailable) { }
}
