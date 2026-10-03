package io.agentflow.budget;

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
 * 预算调整申请的本人操作与按轮次读取；批准依据和实际额度执行分别管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentService {
    private final BudgetAdjustmentRepository requests;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;

    /** 复用原申请授权与敏感字段投影，不能根据管理员身份直接返回明细。 */
    public BudgetAdjustmentService(BudgetAdjustmentRepository requests, ApprovalApplicationFacade applications, ApplicationFieldViews fields,
                                     CurrentActor actors) {
        this.requests = requests; this.applications = applications; this.fields = fields; this.actors = actors;
    }

    /** 草稿只创建独立申请绑定，不占用原预算台账。 */
    @Transactional
    public Receipt create(String businessNo, String processKey, long definitionVersion, BudgetAdjustmentContent content) {
        var actor = actors.actor(); UUID id = UUID.randomUUID();
        var application = applications.createBusiness(businessNo, processKey, definitionVersion, content.title(), BudgetAdjustmentFormContract.draftPayload(),
                new BusinessReference(BusinessReference.Type.BUDGET_ADJUSTMENT, id));
        var adjustment = BudgetAdjustmentRequest.draft(id, actor.tenantId(), application.id(), actor.userId(), content);
        requests.create(adjustment, actor.userId()); return receipt(application, adjustment);
    }

    /** 同一事务核对两份版本并保留完整修订证据。 */
    @Transactional
    public Receipt revise(UUID id, long applicationVersion, long requestVersion, BudgetAdjustmentContent content) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var adjustment = owned(id);
        var application = applications.requireApplicant(adjustment.applicationId()); application.requireEditable(applicationVersion);
        adjustment.revise(requestVersion, content);
        application = applications.reviseBusiness(application.id(), applicationVersion, content.title(), BudgetAdjustmentFormContract.draftPayload(), application.businessReference());
        requests.update(adjustment, requestVersion, actors.actor().userId(), "REVISE"); return receipt(application, adjustment);
    }

    /** 旧审批人只能读取其有权查看的冻结轮次，不能读取退回后的补正草稿。 */
    @Transactional(readOnly = true)
    public View read(UUID id, Integer roundNo) {
        var adjustment = requests.find(actors.actor().tenantId(), id).orElseThrow(BudgetAdjustmentService::notFound);
        var application = applications.get(adjustment.applicationId()); boolean owner = adjustment.employeeId().equals(actors.actor().userId());
        // 作废可能发生在补正之后，本人当前内容不能被上一轮提交快照替代。
        if (roundNo == null && (application.editable() || application.status() == ApplicationStatus.CANCELLED || adjustment.rounds().isEmpty())) {
            if (!owner) throw notFound();
            return view(application, adjustment, null, application.editable());
        }
        int selected = roundNo == null ? application.roundNo() : roundNo;
        var round = adjustment.rounds().stream().filter(value -> value.roundNo() == selected).findFirst().orElseThrow(BudgetAdjustmentService::notFound);
        var projection = fields.attachmentView(application, selected);
        if (!owner && !BudgetAdjustmentFormContract.detailsReadable(application.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading adjustment details");
        }
        return view(application, adjustment, round, false);
    }

    /** 撤回或作废只改变申请生命周期，冻结的历史依据始终保留。 */
    @Transactional
    public Receipt change(UUID id, long applicationVersion, long requestVersion, String comment, boolean cancel) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var adjustment = owned(id);
        if (adjustment.version() != requestVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Budget adjustment version changed");
        var application = applications.requireApplicant(adjustment.applicationId());
        application = cancel ? applications.cancelBusiness(application.id(), applicationVersion, comment, application.businessReference())
                : applications.withdrawBusiness(application.id(), applicationVersion, comment, application.businessReference());
        return receipt(application, adjustment);
    }

    private BudgetAdjustmentRequest owned(UUID id) {
        return requests.find(actors.actor().tenantId(), id).filter(adjustment -> adjustment.employeeId().equals(actors.actor().userId())).orElseThrow(BudgetAdjustmentService::notFound);
    }
    private static View view(Application application, BudgetAdjustmentRequest adjustment, BudgetAdjustmentRound round, boolean editable) {
        return new View(adjustment.id(), application.id(), application.businessNo(), application.status(), application.version(), adjustment.version(),
                round == null ? application.roundNo() : round.roundNo(), editable, round == null ? adjustment.content() : round.content(),
                round == null ? null : BudgetAdjustmentRoundView.of(round),
                round != null && adjustment.approval() != null && adjustment.approval().roundNo() == round.roundNo() ? adjustment.approval() : null);
    }
    /** 回执不缓存完整敏感内容，恢复后通过权限查询重新读取。 */
    public static Receipt receipt(Application application, BudgetAdjustmentRequest adjustment) {
        return new Receipt(adjustment.id(), application.id(), application.version(), adjustment.version(), application.roundNo(), application.status());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Budget adjustment or round not found"); }

    /**
     * 草稿内容与某轮快照互斥，旧审批人只能读取其有权访问的原轮次。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID applicationId, String businessNo, ApplicationStatus status, long applicationVersion, long requestVersion,
                       int roundNo, boolean editable, BudgetAdjustmentContent content, BudgetAdjustmentRoundView financialRound, BudgetAdjustmentRequest.Approval approval) { }
    /**
     * 幂等业务操作的最小结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, UUID applicationId, long applicationVersion, long requestVersion, int roundNo, ApplicationStatus status) { }
}
