package io.agentflow.approval.workspace;

import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 当前账号的个人工作台查询，不接受客户端指定查询他人或其他租户。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
    private final CurrentActor currentActor;
    private final WorkspaceReadPort reader;
    private final JsonUtil json;

    /** 组合认证上下文、入口校验与只读投影端口。 */
    public WorkspaceController(CurrentActor currentActor, WorkspaceReadPort reader, JsonUtil json) {
        this.currentActor = currentActor; this.reader = reader; this.json = json;
    }

    /** 我发起包含本人创建的所有状态；我的草稿只包含 DRAFT。 */
    @GetMapping("/applications")
    public Page<WorkspaceReadPort.ApplicationItem> applications(@RequestParam Map<String, String> raw) {
        Actor actor = currentActor.actor();
        WorkspaceQueryParameters parameters = WorkspaceQueryParameters.parse(actor, false, raw, json);
        var rows = reader.applications(actor, parameters.query());
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        String cursor = rows.size() > count ? parameters.cursor(last.createdAt(), last.id()) : null;
        return new Page<>(List.copyOf(rows.subList(0, count)), cursor);
    }

    /** 已办按实际动作逐条展示，领取和释放不当作已办决定。 */
    @GetMapping("/handled")
    public Page<WorkspaceReadPort.HandledItem> handled(@RequestParam Map<String, String> raw) {
        Actor actor = currentActor.actor();
        WorkspaceQueryParameters parameters = WorkspaceQueryParameters.parse(actor, true, raw, json);
        var rows = reader.handled(actor, parameters.query());
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        String cursor = rows.size() > count ? parameters.cursor(last.handledAt(), last.id()) : null;
        return new Page<>(List.copyOf(rows.subList(0, count)), cursor);
    }

    /**
     * 有界摘要页，游标为空表示没有下一页。
     * @author owlzhangfq@gmail.com
     */
    public record Page<T>(List<T> items, String nextCursor) { }
}
