package io.agentflow.agent;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人执行用量查询，角色或查询参数不能切换租户和运行所有人。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class AgentUsageController {
    private final CurrentActor actors;
    private final AgentUsageRepository repository;
    /** 当前身份只来自请求认证上下文。 */
    public AgentUsageController(CurrentActor actors, AgentUsageRepository repository) { this.actors = actors; this.repository = repository; }

    /** 返回最近 100 次执行；未完成观测不推断外部执行成功或失败。 */
    @GetMapping("/api/v1/agent-executions/usage")
    public ResponseEntity<List<AgentExecutionUsage>> list(@RequestParam MultiValueMap<String, String> raw) {
        UUID subject = null;
        try {
            if (!Set.of("subjectId").containsAll(raw.keySet()) || raw.values().stream().anyMatch(values -> values.size() != 1)) throw new IllegalArgumentException();
            if (raw.containsKey("subjectId")) {
                subject = UUID.fromString(raw.getFirst("subjectId"));
                if (!subject.toString().equals(raw.getFirst("subjectId"))) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw new DomainException("INVALID_AGENT_QUERY", "Invalid agent usage query"); }
        var actor = actors.actor();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(repository.list(actor.tenantId(), actor.userId(), subject));
    }
}
