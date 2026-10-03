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
 * 事前计划的申请人操作与按轮次读取；批准额度由实际审批完成事务创建。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePlanService {
    private final ExpensePlanRepository plans;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;

    /** 复用原申请授权与敏感字段投影，不能根据管理员身份直接返回明细。 */
    public ExpensePlanService(ExpensePlanRepository plans, ApprovalApplicationFacade applications, ApplicationFieldViews fields, CurrentActor actors) {
        this.plans = plans; this.applications = applications; this.fields = fields; this.actors = actors;
    }

    /** 草稿只创建独立申请绑定，不产生余额。 */
    @Transactional
    public View create(String businessNo, String processKey, long definitionVersion, ExpensePlanContent content) {
        var actor = actors.actor(); UUID id = UUID.randomUUID();
        var application = applications.createBusiness(businessNo, processKey, definitionVersion, content.title(), ExpensePlanFormContract.draftPayload(),
                new BusinessReference(BusinessReference.Type.EXPENSE_PLAN, id));
        var plan = ExpensePlan.draft(id, actor.tenantId(), application.id(), actor.userId(), content);
        plans.create(plan, actor.userId()); return view(application, plan, null, true);
    }

    /** 同一事务核对两份版本并保留完整修订证据。 */
    @Transactional
    public View revise(UUID id, long applicationVersion, long planVersion, ExpensePlanContent content) {
        owned(id); plans.lock(actors.actor().tenantId(), id); var plan = owned(id);
        var application = applications.requireApplicant(plan.applicationId()); application.requireEditable(applicationVersion);
        plan.revise(planVersion, content);
        application = applications.reviseBusiness(application.id(), applicationVersion, content.title(), ExpensePlanFormContract.draftPayload(), application.businessReference());
        plans.update(plan, planVersion, actors.actor().userId(), "REVISE"); return view(application, plan, null, true);
    }

    /** 旧审批人只能读取其有权查看的冻结轮次，不能读取退回后的补正草稿。 */
    @Transactional(readOnly = true)
    public View read(UUID id, Integer roundNo) {
        var plan = plans.find(actors.actor().tenantId(), id).orElseThrow(ExpensePlanService::notFound);
        var application = applications.get(plan.applicationId()); boolean owner = plan.employeeId().equals(actors.actor().userId());
        // 作废可能发生在补正之后，本人当前内容不能被上一轮提交快照替代。
        if (roundNo == null && (application.editable() || application.status() == ApplicationStatus.CANCELLED || plan.rounds().isEmpty())) {
            if (!owner) throw notFound();
            return view(application, plan, null, application.editable());
        }
        int selected = roundNo == null ? application.roundNo() : roundNo;
        var round = plan.rounds().stream().filter(value -> value.roundNo() == selected).findFirst().orElseThrow(ExpensePlanService::notFound);
        var projection = fields.attachmentView(application, selected);
        if (!owner && !ExpensePlanFormContract.detailsReadable(application.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading planned expense details");
        }
        return view(application, plan, round, false);
    }

    /** 撤回供本人补正，作废终止未批准计划；两者都不伪造或释放已批准额度。 */
    @Transactional
    public Receipt change(UUID id, long applicationVersion, long planVersion, String comment, boolean cancel) {
        owned(id); plans.lock(actors.actor().tenantId(), id); var plan = owned(id);
        if (plan.version() != planVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Expense plan version changed");
        var application = applications.requireApplicant(plan.applicationId());
        application = cancel ? applications.cancelBusiness(application.id(), applicationVersion, comment, application.businessReference())
                : applications.withdrawBusiness(application.id(), applicationVersion, comment, application.businessReference());
        return receipt(application, plan);
    }

    private ExpensePlan owned(UUID id) {
        return plans.find(actors.actor().tenantId(), id).filter(plan -> plan.employeeId().equals(actors.actor().userId())).orElseThrow(ExpensePlanService::notFound);
    }
    private static View view(Application application, ExpensePlan plan, ExpensePlanRound round, boolean editable) {
        return new View(plan.id(), application.id(), application.businessNo(), application.status(), application.version(), plan.version(),
                round == null ? application.roundNo() : round.roundNo(), editable, round == null ? plan.content() : round.content(), round);
    }
    /** 回执不缓存完整敏感内容，恢复后通过权限查询重新读取。 */
    public static Receipt receipt(Application application, ExpensePlan plan) {
        return new Receipt(plan.id(), application.id(), application.version(), plan.version(), application.roundNo(), application.status());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Expense plan or round not found"); }

    /**
     * 草稿内容与某轮快照互斥，余额由独立的核销资源接口读取。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID applicationId, String businessNo, ApplicationStatus status, long applicationVersion, long planVersion,
                       int roundNo, boolean editable, ExpensePlanContent content, ExpensePlanRound financialRound) { }
    /**
     * 幂等业务操作的最小结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, UUID applicationId, long applicationVersion, long planVersion, int roundNo, ApplicationStatus status) { }
}
