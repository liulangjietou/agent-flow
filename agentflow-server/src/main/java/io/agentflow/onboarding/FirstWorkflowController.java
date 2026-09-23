package io.agentflow.onboarding;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理员读取当前租户的首次流程运行证据，身份与租户不由参数覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class FirstWorkflowController {
    private final CurrentActor currentActor;
    private final FirstWorkflowReadPort reader;

    /** 注入认证上下文与只读事实端口。 */
    public FirstWorkflowController(CurrentActor currentActor, FirstWorkflowReadPort reader) {
        this.currentActor = currentActor; this.reader = reader;
    }

    /** 查询只接受可选 definitionId，跨租户定义返回不存在。 */
    @GetMapping("/api/v1/system/first-workflow")
    public ResponseEntity<FirstWorkflowReadPort.Report> progress(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        actor.requireRole("ADMIN");
        if (!Set.of("definitionId").containsAll(raw.keySet())) throw invalid();
        UUID id = null;
        if (raw.containsKey("definitionId")) {
            try {
                id = UUID.fromString(raw.get("definitionId"));
                if (!id.toString().equalsIgnoreCase(raw.get("definitionId"))) throw invalid();
            } catch (IllegalArgumentException exception) { throw invalid(); }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reader.read(actor.tenantId(), id, Instant.now()));
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_FIRST_WORKFLOW_QUERY", "Invalid first workflow query");
    }
}
