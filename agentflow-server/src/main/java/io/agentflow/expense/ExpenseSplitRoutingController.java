package io.agentflow.expense;

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
 * 跨单路由依据只读入口，只接受一个明确的原轮次，身份取当前会话。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/split-routing")
public class ExpenseSplitRoutingController {
    private final ExpenseSplitRoutingQueries queries;

    /** 参数校验和 HTTP 缓存边界留在入口，逐单授权由查询服务负责。 */
    public ExpenseSplitRoutingController(ExpenseSplitRoutingQueries queries) { this.queries = queries; }

    /** 拒绝默认轮次、重复参数和额外身份参数，避免不明确的读取范围。 */
    @GetMapping
    public ResponseEntity<ExpenseSplitRoutingQueries.View> read(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.keySet().equals(Set.of("roundNo")) || raw.get("roundNo").size() != 1) throw invalid();
        String value = raw.getFirst("roundNo");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalid();
        final int roundNo;
        try { roundNo = Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.read(id, roundNo));
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Select exactly one canonical positive submission round"); }
}
