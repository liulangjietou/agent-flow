package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 申请检索入口，管理员运营视图与参与者视图共用摘要分页，不开放租户覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ApplicationSearchController {
    private final CurrentActor currentActor;
    private final ApplicationSearchPort reader;
    private final JsonUtil json;

    /** 注入认证主体、只读端口和统一 JSON 工具。 */
    public ApplicationSearchController(CurrentActor currentActor, ApplicationSearchPort reader, JsonUtil json) {
        this.currentActor = currentActor; this.reader = reader; this.json = json;
    }

    /** 运营检索和导出仍仅对租户管理员开放。 */
    @GetMapping("/api/v1/operations/applications")
    public ResponseEntity<Page> search(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor(); actor.requireRole("ADMIN");
        return page(actor, raw);
    }

    /** 查询当前用户有权读取的申请，流程管理员不会自动获得全租户读取权。 */
    @GetMapping("/api/v1/applications/search")
    public ResponseEntity<Page> visible(@RequestParam Map<String, String> raw) {
        return page(currentActor.actor(), raw);
    }

    private ResponseEntity<Page> page(Actor actor, Map<String, String> raw) {
        var parameters = ApplicationSearchParameters.parse(actor, raw, json);
        var rows = reader.search(actor, parameters.query());
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        String cursor = rows.size() > count ? parameters.cursor(last.createdAt(), last.id()) : null;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Page(List.copyOf(rows.subList(0, count)), cursor));
    }

    /**
     * 有界查询结果，无游标表示没有下一页，不把已加载数量称为总数。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<ApplicationSearchPort.Item> items, String nextCursor) { }
}
