package io.agentflow.approval;

import io.agentflow.common.DomainException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固定轮次父子关系的只读入口，查询不触发子流程激活或新申请创建。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/subprocesses")
public class SubprocessRelationsController {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> PARAMETERS = Set.of("limit", "afterId");
    private final SubprocessRelationsService relations;

    /** 应用服务组合授权和查询，控制器只校验外部参数。 */
    public SubprocessRelationsController(SubprocessRelationsService relations) { this.relations = relations; }

    /** 拒绝重复、未知或不规范参数，游标的原轮次归属由仓储复核。 */
    @GetMapping
    public ResponseEntity<SubprocessRelationsService.Page> read(@PathVariable UUID id, @PathVariable String roundNo,
                                                                @RequestParam MultiValueMap<String, String> parameters) {
        int round, limit; UUID afterId;
        try {
            if (!PARAMETERS.containsAll(parameters.keySet()) || parameters.values().stream().anyMatch(values -> values.size() != 1)) throw new IllegalArgumentException();
            round = positive(roundNo);
            limit = parameters.containsKey("limit") ? positive(parameters.getFirst("limit")) : DEFAULT_LIMIT;
            if (limit > MAX_LIMIT) throw new IllegalArgumentException();
            String cursor = parameters.getFirst("afterId");
            afterId = cursor == null ? null : UUID.fromString(cursor);
            if (afterId != null && !afterId.toString().equals(cursor)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new DomainException("INVALID_SUBPROCESS_QUERY", "A positive round, bounded limit and original call cursor are required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(relations.read(id, round, afterId, limit));
    }

    private static int positive(String value) {
        if (!value.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException();
        return Integer.parseInt(value);
    }
}
