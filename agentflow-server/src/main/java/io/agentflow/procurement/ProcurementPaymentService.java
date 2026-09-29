package io.agentflow.procurement;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 采购付款申请的本人操作与按轮次读取；批准依据和实际付款核销分别管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcurementPaymentService {
    private final ProcurementPaymentRepository requests;
    private final ApprovalApplicationFacade applications;
    private final ApplicationFieldViews fields;
    private final CurrentActor actors;
    private final ProcurementPayableReservations reservations;

    /** 复用原申请授权与敏感字段投影，不能根据管理员身份直接返回明细。 */
    public ProcurementPaymentService(ProcurementPaymentRepository requests, ApprovalApplicationFacade applications, ApplicationFieldViews fields,
                                     CurrentActor actors, ProcurementPayableReservations reservations) {
        this.requests = requests; this.applications = applications; this.fields = fields; this.actors = actors; this.reservations = reservations;
    }

    /** 草稿只创建独立申请绑定，不占用原应付。 */
    @Transactional
    public Receipt create(String businessNo, String processKey, long definitionVersion, ProcurementPaymentContent content) {
        var actor = actors.actor(); UUID id = UUID.randomUUID();
        var application = applications.createBusiness(businessNo, processKey, definitionVersion, content.title(), ProcurementPaymentFormContract.draftPayload(),
                new BusinessReference(BusinessReference.Type.PROCUREMENT_PAYMENT, id));
        var payment = ProcurementPaymentRequest.draft(id, actor.tenantId(), application.id(), actor.userId(), content);
        requests.create(payment, actor.userId()); return receipt(application, payment);
    }

    /** 同一事务核对两份版本并保留完整修订证据。 */
    @Transactional
    public Receipt revise(UUID id, long applicationVersion, long requestVersion, ProcurementPaymentContent content) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var payment = owned(id);
        var application = applications.requireApplicant(payment.applicationId()); application.requireEditable(applicationVersion);
        payment.revise(requestVersion, content);
        application = applications.reviseBusiness(application.id(), applicationVersion, content.title(), ProcurementPaymentFormContract.draftPayload(), application.businessReference());
        requests.update(payment, requestVersion, actors.actor().userId(), "REVISE"); return receipt(application, payment);
    }

    /** 旧审批人只能读取其有权查看的冻结轮次，不能读取退回后的补正草稿。 */
    @Transactional(readOnly = true)
    public View read(UUID id, Integer roundNo) {
        var payment = requests.find(actors.actor().tenantId(), id).orElseThrow(ProcurementPaymentService::notFound);
        var application = applications.get(payment.applicationId()); boolean owner = payment.employeeId().equals(actors.actor().userId());
        // 作废可能发生在补正之后，本人当前内容不能被上一轮提交快照替代。
        if (roundNo == null && (application.editable() || application.status() == ApplicationStatus.CANCELLED || payment.rounds().isEmpty())) {
            if (!owner) throw notFound();
            return view(application, payment, null, application.editable());
        }
        int selected = roundNo == null ? application.roundNo() : roundNo;
        var round = payment.rounds().stream().filter(value -> value.roundNo() == selected).findFirst().orElseThrow(ProcurementPaymentService::notFound);
        var projection = fields.attachmentView(application, selected);
        if (!owner && !ProcurementPaymentFormContract.detailsReadable(application.formSchema(), projection.schema())) {
            throw new DomainException("FORBIDDEN", "Field permissions do not allow reading payment details");
        }
        return view(application, payment, round, false);
    }

    /** 撤回保留应付占用供补正；作废在同一事务释放原占用。 */
    @Transactional
    public Receipt change(UUID id, long applicationVersion, long requestVersion, String comment, boolean cancel) {
        owned(id); requests.lock(actors.actor().tenantId(), id); var payment = owned(id);
        if (payment.version() != requestVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Procurement payment version changed");
        var application = applications.requireApplicant(payment.applicationId());
        application = cancel ? applications.cancelBusiness(application.id(), applicationVersion, comment, application.businessReference())
                : applications.withdrawBusiness(application.id(), applicationVersion, comment, application.businessReference());
        if (cancel) reservations.releaseStopped(application, actors.actor().userId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        return receipt(application, payment);
    }

    private ProcurementPaymentRequest owned(UUID id) {
        return requests.find(actors.actor().tenantId(), id).filter(payment -> payment.employeeId().equals(actors.actor().userId())).orElseThrow(ProcurementPaymentService::notFound);
    }
    private static View view(Application application, ProcurementPaymentRequest payment, ProcurementPaymentRound round, boolean editable) {
        return new View(payment.id(), application.id(), application.businessNo(), application.status(), application.version(), payment.version(),
                round == null ? application.roundNo() : round.roundNo(), editable, round == null ? payment.content() : round.content(),
                round == null ? null : ProcurementPaymentRoundView.of(round),
                round != null && payment.approval() != null && payment.approval().roundNo() == round.roundNo() ? payment.approval() : null);
    }
    /** 回执不缓存完整敏感内容，恢复后通过权限查询重新读取。 */
    public static Receipt receipt(Application application, ProcurementPaymentRequest payment) {
        return new Receipt(payment.id(), application.id(), application.version(), payment.version(), application.roundNo(), application.status());
    }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Procurement payment or round not found"); }

    /**
     * 草稿内容与某轮快照互斥，旧审批人只能读取其有权访问的原轮次。
     * @author owlzhangfq@gmail.com
     */
    public record View(UUID id, UUID applicationId, String businessNo, ApplicationStatus status, long applicationVersion, long requestVersion,
                       int roundNo, boolean editable, ProcurementPaymentContent content, ProcurementPaymentRoundView financialRound, ProcurementPaymentRequest.Approval approval) { }
    /**
     * 幂等业务操作的最小结果。
     * @author owlzhangfq@gmail.com
     */
    public record Receipt(UUID id, UUID applicationId, long applicationVersion, long requestVersion, int roundNo, ApplicationStatus status) { }
}
