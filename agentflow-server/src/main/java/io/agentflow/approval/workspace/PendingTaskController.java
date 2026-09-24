package io.agentflow.approval.workspace;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.JsonUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 待办只读查询入口，客户端不能指定他人队列或绕过资源授权。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/workspace/tasks")
public class PendingTaskController {
    private final CurrentActor currentActor;
    private final PendingTaskReadPort reader;
    private final JsonUtil json;

    /** 组合当前主体、入口校验与查询端口。 */
    public PendingTaskController(CurrentActor currentActor, PendingTaskReadPort reader, JsonUtil json) {
        this.currentActor = currentActor; this.reader = reader; this.json = json;
    }

    /** 页与计数均使用相同授权条件；并发办理后刷新可获得最新结果。 */
    @GetMapping
    public Page list(@RequestParam Map<String, String> raw) {
        var actor = currentActor.actor();
        var parameters = TaskQueryParameters.parse(actor, raw, json);
        var result = reader.read(actor, parameters.query());
        var rows = result.items();
        int count = Math.min(rows.size(), parameters.query().limit());
        var last = count == 0 ? null : rows.get(count - 1);
        return new Page(List.copyOf(rows.subList(0, count)), rows.size() > count ? parameters.cursor(last.createdAt(), last.taskId()) : null,
                result.total());
    }

    /**
     * 摘要页与当前筛选总数；没有表单正文，也不能用列表快照直接办理。
     * @author owlzhangfq@gmail.com
     */
    public record Page(List<PendingTaskReadPort.Item> items, String nextCursor, long total) { }
}
