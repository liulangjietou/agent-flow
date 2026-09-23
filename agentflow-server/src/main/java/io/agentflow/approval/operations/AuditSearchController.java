package io.agentflow.approval.operations;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 管理员跨申请审计入口，权限、筛选与游标在边界一次校验。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class AuditSearchController {
    private final CurrentActor currentActor;
    private final AuditSearchPort reader;
    private final JsonUtil json;

    /** 注入当前主体、查询端口和统一 JSON 工具。 */
    public AuditSearchController(CurrentActor currentActor, AuditSearchPort reader, JsonUtil json) {
        this.currentActor = currentActor; this.reader = reader; this.json = json;
    }

    /** 仅 ADMIN 可查看当前租户的操作事实，流程管理员不自动获得该权限。 */
    @GetMapping("/api/v1/operations/audit")
    public ResponseEntity<Page> search(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        var parameters = AuditSearchParameters.parse(actor, raw, json);
        var rows = reader.search(actor.tenantId(), parameters.query());
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        String cursor = rows.size() > count ? parameters.cursor(last.occurredAt(), last.id()) : null;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Page(List.copyOf(rows.subList(0, count)), cursor));
    }

    /**
     * 有界事件列表，不把已加载数量称为全库总数。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<AuditSearchPort.Item> items, String nextCursor) { }
}
