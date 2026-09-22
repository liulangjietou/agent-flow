package io.agentflow.approval.history;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 申请历史只读接口，复用详情授权后才调用查询编排。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}")
public class ApplicationHistoryController {
    private final ApprovalApplicationFacade applications;
    private final ApprovalHistoryQueryService query;

    /** 组合授权入口与独立历史查询服务。 */
    public ApplicationHistoryController(ApprovalApplicationFacade applications, SubmissionRoundRepository rounds,
                                         ProcessHistoryPort processHistory, AuditHistoryPort auditHistory) {
        this.applications = applications;
        this.query = new ApprovalHistoryQueryService(rounds, processHistory, auditHistory);
    }

    /** 时间线包含真实操作、节点进入结束以及独立的轮次事实标识。 */
    @GetMapping("/timeline")
    public HistoryPage timeline(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return read(id, true, parameters);
    }

    /** 审计只返回真实追加事件，不从节点状态或快照补造操作。 */
    @GetMapping("/audit")
    public HistoryPage audit(@PathVariable UUID id, @RequestParam Map<String, String> parameters) {
        return read(id, false, parameters);
    }

    private HistoryPage read(UUID id, boolean timeline, Map<String, String> raw) {
        HistoryQueryParameters parameters = HistoryQueryParameters.parse(id, timeline, raw);
        Application application = applications.get(id);
        return parameters.page(query.read(application, timeline));
    }
}
