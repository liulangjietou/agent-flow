package io.agentflow.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组合固定调用与两端的独立申请授权；关联事实不能变成表单或附件授权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SubprocessRelationsService {
    private final ApprovalApplicationFacade applications;
    private final SubmissionRoundRepository rounds;
    private final SubprocessCallRepository calls;

    /** 使用既有申请授权用例，不新增父子继承权限或审批参与身份。 */
    public SubprocessRelationsService(ApprovalApplicationFacade applications, SubmissionRoundRepository rounds, SubprocessCallRepository calls) {
        this.applications = applications; this.rounds = rounds; this.calls = calls;
    }

    /** 每页仅展开直接后代；目标不可读时不返回其申请编号、流程、标题或结论。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page read(UUID applicationId, int roundNo, UUID afterId, int limit) {
        var source = applications.get(applicationId);
        var round = rounds.findByRound(source.tenantId(), applicationId, roundNo)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round is not available"));
        var origin = calls.findByChild(source.tenantId(), applicationId).orElse(null);
        if (origin != null && (roundNo != SubprocessCall.CHILD_ROUND || !round.processInstanceId().equals(origin.childProcessInstanceId())
                || round.definitionVersion() != origin.policy().version() || !source.processKey().equals(origin.policy().processKey()))) throw unavailable();
        var parent = origin == null ? null : reference(origin.parentApplicationId(), origin.parentRoundNo(), origin.parentProcessInstanceId());
        var page = calls.pageByParentRound(source.tenantId(), applicationId, roundNo, afterId, limit + 1);
        var visible = page.stream().limit(limit).map(call -> {
            if (!round.processInstanceId().equals(call.parentProcessInstanceId())) throw unavailable();
            return new Child(call.id(), call.nodeId(), call.nodeName(), call.createdAt(),
                    reference(call.childApplicationId(), SubprocessCall.CHILD_ROUND, call.childProcessInstanceId()));
        }).toList();
        return new Page(applicationId, roundNo, Instant.now(), origin != null, parent, visible,
                page.size() > limit ? visible.get(visible.size() - 1).id() : null);
    }

    private Reference reference(UUID id, int roundNo, String processInstanceId) {
        try {
            var application = applications.get(id);
            return rounds.findByRound(application.tenantId(), id, roundNo)
                    .filter(round -> round.processInstanceId().equals(processInstanceId) && round.definitionVersion() == application.definitionVersion())
                    .map(round -> new Reference(id, application.businessNo(), application.processKey(), round.definitionVersion(), roundNo, round.title(), round.status()))
                    .orElse(null);
        } catch (DomainException exception) {
            if ("NOT_FOUND".equals(exception.code())) return null;
            throw exception;
        }
    }

    private static DomainException unavailable() { return new DomainException("SUBPROCESS_RELATION_UNAVAILABLE", "Subprocess relation does not match the saved round"); }

    /** 已授权的最小原轮次引用，不包含表单、自由文本意见、发起任职或附件。@author owlzhangfq@gmail.com */
    public record Reference(UUID applicationId, String businessNo, String processKey, long definitionVersion,
                            int roundNo, String title, SubmissionRound.Status status) { }

    /** 调用节点属于已授权的父轮次，目标申请仍可能不可读。@author owlzhangfq@gmail.com */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Child(UUID id, String nodeId, String nodeName, Instant createdAt, Reference target) { }

    /** 没有父调用与来源不可读分别表示；空页不推断尚未激活的流程路径。@author owlzhangfq@gmail.com */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(UUID applicationId, int roundNo, Instant observedAt, boolean childApplication,
                       Reference parent, List<Child> children, UUID nextAfterId) { }
}
