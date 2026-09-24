package io.agentflow.agent;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/**
 * Agent 记录复用申请实时授权；本入口只读，不启动模型或变更审批。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/assist-runs")
public class AssistRunController {
    private final ApprovalApplicationFacade applications;
    private final AssistRunQueryService queries;
    private final CurrentActor currentActor;

    /** 授权与运行查询分别由已有审批服务和 Agent 应用服务负责。 */
    public AssistRunController(ApprovalApplicationFacade applications, AssistRunQueryService queries, CurrentActor currentActor) {
        this.applications = applications; this.queries = queries; this.currentActor = currentActor;
    }

    /** 每一页重新核对申请可见性，游标不会保留已撤销的权限。 */
    @GetMapping
    public ResponseEntity<AssistRunQueryService.Page> list(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        var parameters = AssistRunQueryParameters.parse(currentActor.actor(), id, raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(applications.get(id), parameters));
    }

    /** 申请归属、租户和实时可见性同时约束详情，响应不进入浏览器缓存。 */
    @GetMapping("/{runId}")
    public ResponseEntity<AssistRunQueryService.Detail> get(@PathVariable UUID id, @PathVariable UUID runId,
                                                          @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.isEmpty()) throw new DomainException("INVALID_AGENT_QUERY", "Assist run detail does not accept query parameters");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.get(applications.get(id), runId));
    }
}
