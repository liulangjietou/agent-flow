package io.agentflow.expense;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 借款申请的本人操作与按轮次读取；批准依据和实际到账余额分别管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AdvanceRequestService {
    private final AdvanceRequestRepository requests;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;

    /** 复用原申请授权与敏感字段投影，不能根据管理员身份直接返回明细。 */
    public AdvanceRequestService(AdvanceRequestRepository requests, ApprovalApplicationFacade applications, ApplicationFieldViews fields, CurrentActor actors) {
        this.requests = requests; this.applications = applications; this.fields = fields; this.actors = actors;
    }

    /** 草稿只创建独立申请绑定，不产生余额。 */
    @Transactional
    public Receipt create(String businessNo, String processKey, long definitionVersion, AdvanceRequestContent content) {
        var actor = actors.actor(); UUID id = UUID.randomUUID();
        var application = applications.createBusiness(businessNo, processKey, definitionVersion, content.title(), AdvanceRequestFormContract.draftPayload(),
                new BusinessReference(BusinessReference.Type.ADVANCE_REQUEST, id));
        var advance = AdvanceRequest.draft(id, actor.tenantId(), application.id(), actor.userId(), content);
        requests.create(advance, actor.userId()); return receipt(application, advance);
    }

    /** 同一事务核对两份版本并保留完整修订证据。 */
    @Transactional
    public Receipt revise(UUID id, long applicationVersion, long requestVersion, AdvanceRequestContent content) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var advance = owned(id);
        var application = applications.requireApplicant(advance.applicationId()); application.requireEditable(applicationVersion);
        advance.revise(requestVersion, content);
        application = applications.reviseBusiness(application.id(), applicationVersion, content.title(), AdvanceRequestFormContract.draftPayload(), application.businessReference());
        requests.update(advance, requestVersion, actors.actor().userId(), "REVISE"); return receipt(application, advance);
    }

    /** 旧审批人只能读取其有权查看的冻结轮次，不能读取退回后的补正草稿。 */
    @Transactional(readOnly = true)
    public View read(UUID id, Integer roundNo) {
        var advance = requests.find(actors.actor().tenantId(), id).orElseThrow(AdvanceRequestService::notFound);
        var application = applications.get(advance.applicationId()); boolean owner = advance.employeeId().equals(actors.actor().userId());
        // 作废可能发生在补正之后，本人当前内容不能被上一轮提交快照替代。
        if (roundNo == null && (application.editable() || application.status() == ApplicationStatus.CANCELLED || advance.rounds().isEmpty())) {
            if (!owner) throw notFound();
            return view(application, advance, null, application.editable());
        }
        int selected = roundNo == null ? application.roundNo() : roundNo;
        var round = advance.rounds().stream().filter(value -> value.roundNo() == selected).findFirst().orElseThrow(AdvanceRequestService::notFound);
        var projection = fields.attachmentView(application, selected);
        if (!owner && !AdvanceRequestFormContract.detailsReadable(application.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading advance details");
        }
        return view(application, advance, round, false);
    }

    /** 撤回供本人补正，作废终止未批准申请；不更改任何实际放款余额。 */
    @Transactional
    public Receipt change(UUID id, long applicationVersion, long requestVersion, String comment, boolean cancel) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var advance = owned(id);
        if (advance.version() != requestVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Advance request version changed");
        var application = applications.requireApplicant(advance.applicationId());
        application = cancel ? applications.cancelBusiness(application.id(), applicationVersion, comment, application.businessReference())
                : applications.withdrawBusiness(application.id(), applicationVersion, comment, application.businessReference());
        return receipt(application, advance);
    }

    private AdvanceRequest owned(UUID id) {
        return requests.find(actors.actor().tenantId(), id).filter(advance -> advance.employeeId().equals(actors.actor().userId())).orElseThrow(AdvanceRequestService::notFound);
    }
    private static View view(Application application, AdvanceRequest advance, AdvanceRequestRound round, boolean editable) {
        return new View(advance.id(), application.id(), application.businessNo(), application.status(), application.version(), advance.version(),
                round == null ? application.roundNo() : round.roundNo(), editable, round == null ? advance.content() : round.content(),
                round == null ? null : AdvanceRequestRoundView.of(round),
                round != null && advance.approval() != null && advance.approval().roundNo() == round.roundNo() ? advance.approval() : null);
    }
    /** 回执不缓存完整敏感内容，恢复后通过权限查询重新读取。 */
    public static Receipt receipt(Application application, AdvanceRequest advance) {
        return new Receipt(advance.id(), application.id(), application.version(), advance.version(), application.roundNo(), application.status());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Advance request or round not found"); }

    /**
     * 草稿内容与某轮快照互斥，余额由独立的核销资源接口读取。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID applicationId, String businessNo, ApplicationStatus status, long applicationVersion, long requestVersion,
                       int roundNo, boolean editable, AdvanceRequestContent content, AdvanceRequestRoundView financialRound, AdvanceRequest.Approval approval) { }
    /**
     * 幂等业务操作的最小结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, UUID applicationId, long applicationVersion, long requestVersion, int roundNo, ApplicationStatus status) { }
}
