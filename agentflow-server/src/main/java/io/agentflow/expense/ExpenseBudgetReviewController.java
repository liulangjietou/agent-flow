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
 * 原轮次预算审批只读入口，不接收客户端决策或外部授权凭据。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/budget-review")
public class ExpenseBudgetReviewController {
    private final ExpenseBudgetReviewQuery query;

    /** 参数形状与缓存策略由 HTTP 入口统一处理。 */
    public ExpenseBudgetReviewController(ExpenseBudgetReviewQuery query) { this.query = query; }

    /** 必须明确选择一个原轮次，禁止额外身份参数和重复轮次。 */
    @GetMapping
    public ResponseEntity<ExpenseBudgetReviewQuery.View> read(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.keySet().equals(Set.of("roundNo")) || raw.get("roundNo").size() != 1) throw invalid();
        String value = raw.getFirst("roundNo");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalid();
        final int roundNo;
        try { roundNo = Integer.parseInt(value); } catch (NumberFormatException malformed) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(id, roundNo));
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Select exactly one canonical positive submission round"); }
}
