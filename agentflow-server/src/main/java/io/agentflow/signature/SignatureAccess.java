package io.agentflow.signature;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.attachment.Attachment;
import io.agentflow.attachment.AttachmentService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 签署权限组合既有申请、轮次原件和可信签署配置；审批通过本身不授予签署权。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SignatureAccess {
    private final ApprovalApplicationFacade applications;
    private final AttachmentService attachments;
    private final SubmissionRoundRepository rounds;
    private final OrganizationRepository organization;
    private final SignatureGatewayConfiguration configuration;

    /** 当前权限读取沿用现有入口，签署层只增加明确外发所需的批准来源与资料绑定。 */
    public SignatureAccess(ApprovalApplicationFacade applications, AttachmentService attachments, SubmissionRoundRepository rounds,
            OrganizationRepository organization, SignatureGatewayConfiguration configuration) {
        this.applications = applications; this.attachments = attachments; this.rounds = rounds;
        this.organization = organization; this.configuration = configuration;
    }

    /** 原件、签署人、配置和来源均由服务器取值；调用方只选择原件和已公布资料版本。 */
    public SignatureOperation.Input prepare(Actor actor, UUID applicationId, CreateInput command, Instant now) {
        active(actor);
        var application = applications.getForActor(actor, applicationId);
        approved(application, command.roundNo(), command.expectedVersion());
        var declaration = enabled(actor, command.profileKey(), command.profileVersion());
        var documents = command.documentIds().stream().map(id -> document(actor, applicationId, command.roundNo(), id)).toList();
        var profile = declaration.profile();
        var request = new SignatureRequest(UUID.randomUUID(), actor.tenantId(),
                new SignatureRequest.Source(applicationId, command.roundNo(), application.version(), application.processKey(), application.definitionVersion()),
                new SignatureRequest.Authorization(actor.userId(), profile.key(), profile.version(), profile.digest(), command.purpose(), now, command.validUntil()),
                documents, profile.signers());
        return new SignatureOperation.Input(request, declaration.targetDigest());
    }

    /** 成功请求重放前仍检查当前原件权限，但不重新要求旧批准版本或资料仍可用于新授权。 */
    public List<SignatureRequest.Document> requireSelection(Actor actor, UUID applicationId, int roundNo, List<UUID> documentIds) {
        requireRound(actor, applicationId, roundNo);
        return documentIds.stream().map(id -> document(actor, applicationId, roundNo, id)).toList();
    }

    /** 列表首先复用申请与轮次授权，具体操作再分别检查原件字段权限。 */
    public void requireRound(Actor actor, UUID applicationId, int roundNo) {
        active(actor); applications.getForActor(actor, applicationId);
        rounds.findByRound(actor.tenantId(), applicationId, roundNo)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Submission round not found"));
    }

    /** 可选资料只来自当前具名授权，组织目录不补授签署身份。 */
    public List<SignatureProfile> profiles(Actor actor) {
        active(actor);
        return configuration.declarations().stream().filter(SignatureGatewayConfiguration.Declaration::enabled)
                .map(SignatureGatewayConfiguration.Declaration::profile)
                .filter(value -> value.tenantId().equals(actor.tenantId()) && value.actors().contains(actor.userId())).toList();
    }

    /** 状态与自由文本只向能读取全部原件的当前主体开放；不沿用创建时缓存的权限。 */
    public void requireReadable(Actor actor, SignatureOperation operation) {
        active(actor);
        var request = operation.input().request();
        if (!actor.tenantId().equals(request.tenantId())) throw new DomainException("NOT_FOUND", "Signature operation not found");
        applications.getForActor(actor, request.source().applicationId());
        for (var original : request.documents()) {
            if (!original.equals(document(actor, request.source().applicationId(), request.source().roundNo(), original.attachmentId()))) throw sourceChanged();
        }
    }

    /** 首次领取再次核对原登录对应主体、批准版本和资料；已发送恢复不调用此入口。 */
    public void requireInitialSend(Actor actor, SignatureOperation operation) {
        var request = operation.input().request(); var source = request.source(); var authorization = request.authorization();
        if (!actor.userId().equals(authorization.actor())) throw new DomainException("FORBIDDEN", "Signature authorization belongs to another actor");
        requireReadable(actor, operation);
        var application = applications.getForActor(actor, source.applicationId());
        approved(application, source.roundNo(), source.applicationVersion());
        if (!application.processKey().equals(source.processKey()) || application.definitionVersion() != source.definitionVersion()) throw sourceChanged();
        var declaration = enabled(actor, authorization.profileKey(), authorization.profileVersion());
        if (!declaration.profile().matches(request) || !declaration.targetDigest().equals(operation.input().targetDigest())) throw sourceChanged();
    }

    private SignatureRequest.Document document(Actor actor, UUID applicationId, int round, UUID id) {
        Attachment file = attachments.readableForActor(actor, applicationId, id, round);
        file.requireReady();
        return new SignatureRequest.Document(file.id(), file.contentId(), file.fieldPath(), file.filename(), file.size(), file.sha256());
    }

    private void active(Actor actor) {
        if (organization.initialized(actor.tenantId()) && organization.personBySubject(actor.tenantId(), actor.userId())
                .filter(OrganizationPerson::active).isEmpty()) throw new DomainException("FORBIDDEN", "Signature actor is not active in the organization");
    }

    private void approved(Application application, int roundNo, long expectedVersion) {
        var round = rounds.findByRound(application.tenantId(), application.id(), roundNo).orElseThrow(SignatureAccess::sourceChanged);
        if (application.status() != ApplicationStatus.APPROVED || application.version() != expectedVersion || application.roundNo() != roundNo
                || round.status() != SubmissionRound.Status.APPROVED || round.definitionVersion() != application.definitionVersion()) throw sourceChanged();
    }

    private SignatureGatewayConfiguration.Declaration enabled(Actor actor, String key, long version) {
        return configuration.find(actor.tenantId(), key, version).filter(SignatureGatewayConfiguration.Declaration::enabled)
                .filter(value -> value.profile().actors().contains(actor.userId()))
                .orElseThrow(() -> new DomainException("FORBIDDEN", "Signature profile is not enabled for this actor"));
    }

    private static DomainException sourceChanged() { return new DomainException("SIGNATURE_SOURCE_CHANGED", "Approved signature source or authorization changed"); }

    /**
     * 业务入口一次校验选择范围；不接收租户、操作者、物理路径、服务地址或服务方账户。
     * @author owlzhangfq@gmail.com
     */
    public record CreateInput(int roundNo, long expectedVersion, String profileKey, long profileVersion,
                              List<UUID> documentIds, String purpose, Instant validUntil) {
        public CreateInput {
            if (roundNo < 1 || expectedVersion < 1 || profileVersion < 1 || !SignatureRequest.key(profileKey)
                    || documentIds == null || documentIds.isEmpty() || documentIds.size() > SignatureRequest.MAX_DOCUMENTS
                    || documentIds.stream().anyMatch(java.util.Objects::isNull) || documentIds.stream().distinct().count() != documentIds.size()
                    || !SignatureRequest.literal(purpose, 1000) || validUntil == null) throw new DomainException("INVALID_SIGNATURE_REQUEST", "Signature selection and authorization are invalid");
            documentIds = List.copyOf(documentIds);
        }
        @Override public String toString() { return "SignatureCreateInput[round=" + roundNo + ", documents=" + documentIds.size() + "]"; }
    }
}
