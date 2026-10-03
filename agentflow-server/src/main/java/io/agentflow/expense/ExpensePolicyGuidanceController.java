package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.Set;
import java.util.UUID;

/**
 * 员工填报时的只读制度查询；身份由认证提供，禁止缓存或重复覆盖匹配维度。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpensePolicyGuidanceController {
    private static final Set<String> PARAMETERS = Set.of("legalEntityId", "reportType", "categoryCode", "cityCode", "incurredOn", "currency", "unit");
    private final ExpensePolicyGuidanceService service;

    /** 入口统一校验查询格式，应用服务负责本人授权及版本编排。 */
    public ExpensePolicyGuidanceController(ExpensePolicyGuidanceService service) { this.service = service; }

    /** 不接受员工、职级、城市等级或制度选择覆盖，只返回适用的单条规则。 */
    @GetMapping("/api/v1/finance/expense-policy-guidance")
    public ResponseEntity<ExpensePolicyGuidanceService.View> read(@RequestParam MultiValueMap<String, String> parameters) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.read(context(parameters)));
    }

    private ExpensePolicyGuidance.Context context(MultiValueMap<String, String> parameters) {
        if (!parameters.keySet().equals(PARAMETERS) || parameters.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        try {
            var legal = UUID.fromString(parameters.getFirst("legalEntityId")); var date = LocalDate.parse(parameters.getFirst("incurredOn"));
            if (!legal.toString().equals(parameters.getFirst("legalEntityId")) || !date.toString().equals(parameters.getFirst("incurredOn"))) throw invalid();
            return new ExpensePolicyGuidance.Context(legal, ExpenseContent.Type.valueOf(parameters.getFirst("reportType")), parameters.getFirst("categoryCode"),
                    parameters.getFirst("cityCode"), date, parameters.getFirst("currency"), ExpenseLine.Unit.valueOf(parameters.getFirst("unit")));
        } catch (IllegalArgumentException | DateTimeException malformed) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_GUIDANCE_QUERY", "Expense policy guidance query is invalid"); }
}
