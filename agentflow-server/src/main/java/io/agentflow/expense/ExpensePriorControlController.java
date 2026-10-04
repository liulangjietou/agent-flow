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
 * 额度控制事实只读入口，调用者必须明确选择原审批轮次。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/prior-control")
public class ExpensePriorControlController {
    private final ExpensePriorControlQuery query;

    /** HTTP 参数和缓存策略由入口负责，敏感授权交给费用查询。 */
    public ExpensePriorControlController(ExpensePriorControlQuery query) { this.query = query; }

    /** 不接受额外身份字段、重复参数或推断轮次。 */
    @GetMapping
    public ResponseEntity<ExpensePriorControlQuery.View> read(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.keySet().equals(Set.of("roundNo")) || raw.get("roundNo").size() != 1) throw invalid();
        String value = raw.getFirst("roundNo");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalid();
        final int roundNo;
        try { roundNo = Integer.parseInt(value); } catch (NumberFormatException malformed) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(id, roundNo));
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Select exactly one canonical positive submission round"); }
}
