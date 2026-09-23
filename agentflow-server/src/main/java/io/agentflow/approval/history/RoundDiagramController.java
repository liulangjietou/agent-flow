package io.agentflow.approval.history;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.common.DomainException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/**
 * 轮次流程图只读入口，与申请详情共享授权，在同一数据库快照中读取运行事实。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/rounds/{roundNo}/diagram")
public class RoundDiagramController {
    private final ApprovalApplicationFacade applications;
    private final SubmissionRoundRepository rounds;
    private final RoundDiagramPort diagrams;

    /** 注入授权用例、轮次仓储和流程图防腐端口。 */
    public RoundDiagramController(ApprovalApplicationFacade applications, SubmissionRoundRepository rounds,
                                  RoundDiagramPort diagrams) {
        this.applications = applications;
        this.rounds = rounds;
        this.diagrams = diagrams;
    }

    /** 先授权申请再读取精确轮次；缺少历史时返回不可用，不使用当前定义补图。 */
    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<RoundDiagramPort.Diagram> read(@PathVariable UUID id, @PathVariable String roundNo,
                                                        @RequestParam Map<String, String> parameters) {
        int number;
        try {
            if (!parameters.isEmpty() || !roundNo.matches("[1-9][0-9]{0,9}")) throw new NumberFormatException();
            number = Integer.parseInt(roundNo);
        } catch (NumberFormatException exception) {
            throw new DomainException("INVALID_DIAGRAM_QUERY", "A positive round number and no query parameters are required");
        }
        var application = applications.get(id);
        var round = rounds.findByRound(application.tenantId(), id, number)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round is not available"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagrams.read(application, round));
    }
}
