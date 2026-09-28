package io.agentflow.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.Application;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 在已实时授权的申请中编排运行查询，领域对象不承担 HTTP 展示职责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AssistRunQueryService {
    private final AssistRunReadPort readPort;
    private final AssistRunRepository repository;
    private final io.agentflow.approval.ApplicationFieldViews fields;

    /** 列表使用轻量读模型，详情通过仓储恢复完整领域记录。 */
    public AssistRunQueryService(AssistRunReadPort readPort, AssistRunRepository repository, io.agentflow.approval.ApplicationFieldViews fields) {
        this.readPort = readPort; this.repository = repository;
        this.fields = fields;
    }

    /** 不返回全库数量或正文；末页显式标记为空游标。 */
    @Transactional(readOnly = true)
    public Page list(Application application, AssistRunQueryParameters parameters) {
        var found = readPort.list(application.tenantId(), application.id(), parameters.query());
        var items = found.stream().limit(parameters.query().limit()).toList();
        String next = found.size() > items.size() ? parameters.cursor(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    /** 路径申请必须与运行绑定一致；运行发起人身份不能替代当前申请权限。 */
    @Transactional(readOnly = true)
    public Detail get(Application application, UUID runId) {
        var run = repository.find(application.tenantId(), runId)
                .filter(value -> value.input().applicationId().equals(application.id()))
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Assist run not found"));
        fields.requireFullAssistInput(application, run.input().roundNo());
        return new Detail(run.id(), application.id(), run.input().applicationVersion(), run.input().roundNo(),
                run.status(), run.version(), run.requestedBy(), run.createdAt(), run.startedAt(), run.completedAt(),
                run.promptVersion(), run.input().references(), run.suggestion(), run.failure(), run.review(),
                application.version(), run.input().applicationVersion() == application.version()
                        && run.input().roundNo() == application.roundNo());
    }

    /**
     * 单申请运行目录页。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(List<AssistRunReadPort.Item> items, String nextCursor) { }

    /**
     * 原文与人工复核分离展示，引用只有来源标识及摘要，不解析为地址或当前字段值。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(UUID id, UUID applicationId, long applicationVersion, int roundNo, AssistRun.Status status,
                         long version, String requestedBy, Instant createdAt, Instant startedAt, Instant completedAt,
                         String promptVersion, List<AssistInput.Reference> inputReferences, AssistSuggestion suggestion,
                         AssistRun.Failure failure, AssistRun.Review review, long currentApplicationVersion,
                         boolean inputCurrent) { }
}
