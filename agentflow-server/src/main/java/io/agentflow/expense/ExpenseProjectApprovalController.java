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
 * 仅开放原轮次项目依据查询；身份来自认证上下文，参数不接受负责人或任职覆盖。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/expense-reports/{id}/project-approval")
public class ExpenseProjectApprovalController {
    private final ExpenseProjectApprovalQuery query;

    /** 参数格式在 HTTP 入口集中校验，读取授权由费用查询处理。 */
    public ExpenseProjectApprovalController(ExpenseProjectApprovalQuery query) { this.query = query; }

    /** 明确选择一个规范正整数轮次；敏感响应不缓存。 */
    @GetMapping
    public ResponseEntity<ExpenseProjectApprovalQuery.View> read(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> raw) {
        if (!raw.keySet().equals(Set.of("roundNo")) || raw.get("roundNo").size() != 1) throw invalid();
        String value = raw.getFirst("roundNo");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalid();
        final int round;
        try { round = Integer.parseInt(value); } catch (NumberFormatException malformed) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.read(id, round));
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_QUERY", "Select exactly one canonical positive submission round"); }
}
