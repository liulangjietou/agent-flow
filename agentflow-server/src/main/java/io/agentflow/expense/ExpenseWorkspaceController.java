package io.agentflow.expense;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.UUID;

/**
 * 财务工作区只读入口不接受指定员工或租户，也不把配置状态当作真实资金。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class ExpenseWorkspaceController {
    private final ExpenseWorkspaceQuery queries;
    private final ExpenseWorkflowQuery workflow;
    /** 列表与实时控制各自投影，不扩大普通审批明细权限。 */
    public ExpenseWorkspaceController(ExpenseWorkspaceQuery queries, ExpenseWorkflowQuery workflow) { this.queries = queries; this.workflow = workflow; }

    /** 本人费用列表。 */
    @GetMapping("/api/v1/expense-reports")
    public ResponseEntity<ExpenseWorkspaceQuery.ReportPage> reports(@RequestParam Map<String, String> parameters) { return noStore(queries.reports(parameters)); }

    /** 本人事前批准余额，包括仍有原预留的关闭额度。 */
    @GetMapping("/api/v1/expense-requests")
    public ResponseEntity<ExpenseWorkspaceQuery.PriorPage> requests(@RequestParam Map<String, String> parameters) { return noStore(queries.requests(parameters)); }

    /** 本人已放款借款余额。 */
    @GetMapping("/api/v1/employee-advances")
    public ResponseEntity<ExpenseWorkspaceQuery.AdvancePage> advances(@RequestParam Map<String, String> parameters) { return noStore(queries.advances(parameters)); }

    /** 当前纸件、预算和可办理动作，完整明细字段权限仍需满足。 */
    @GetMapping("/api/v1/expense-reports/{id}/workflow")
    public ResponseEntity<ExpenseWorkflowQuery.View> workflow(@PathVariable UUID id, @RequestParam Map<String, String> parameters) { return noStore(workflow.get(id, parameters)); }

    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
