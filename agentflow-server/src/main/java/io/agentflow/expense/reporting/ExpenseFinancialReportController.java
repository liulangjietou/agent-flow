package io.agentflow.expense.reporting;

import io.agentflow.common.CurrentActor;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财务只读报表入口，角色不替代每份原业务的敏感字段授权。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpenseFinancialReportController {
    private final CurrentActor actors;
    private final ExpenseFinancialReportQuery reports;
    /** 入口校验一次日期和筛选，查询服务统一使用该快照。 */
    public ExpenseFinancialReportController(CurrentActor actors, ExpenseFinancialReportQuery reports) { this.actors = actors; this.reports = reports; }
    /** 认证角色与逐轮权限共同约束结果，浏览器和代理都不得缓存财务汇总。 */
    @GetMapping("/api/v1/reports/expense-finance")
    public ResponseEntity<ExpenseFinancialReport> report(@RequestParam MultiValueMap<String, String> raw) {
        actors.actor().requireRole("FINANCE");
        Instant now = Instant.now();
        var query = ExpenseReportQueryParameters.parse(raw, now.atOffset(ZoneOffset.UTC).toLocalDate());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reports.read(query, now));
    }
}
