package io.agentflow.definition;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 流程目录查询入口，完整定义及写操作继续使用原用例与授权。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class DefinitionCatalogController {
    private final CurrentActor actors;
    private final DefinitionCatalogPort catalog;
    private final JsonUtil json;

    /** 注入当前主体、只读目录和统一 JSON 工具。 */
    public DefinitionCatalogController(CurrentActor actors, DefinitionCatalogPort catalog, JsonUtil json) {
        this.actors = actors; this.catalog = catalog; this.json = json;
    }

    /** 查询当前身份可见的定义摘要，每页重新检查角色及租户。 */
    @GetMapping("/api/v1/process-definitions/search")
    public ResponseEntity<Page> search(@RequestParam Map<String, String> raw) {
        var actor = actors.actor();
        var parameters = DefinitionCatalogParameters.parse(actor, raw, json);
        var rows = catalog.search(actor.tenantId(), parameters.query());
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        String cursor = rows.size() > count ? parameters.cursor(last.createdAt(), last.id()) : null;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Page(List.copyOf(rows.subList(0, count)), cursor));
    }

    /**
     * 已加载条数不代表全库总数；空游标表示没有下一页。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<DefinitionCatalogPort.Item> items, String nextCursor) { }
}
