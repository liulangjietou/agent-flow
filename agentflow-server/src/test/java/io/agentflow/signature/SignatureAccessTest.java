package io.agentflow.signature;

import io.agentflow.approval.ApprovalApplicationFacade;
import io.agentflow.approval.ApplicationFieldViews;
import io.agentflow.approval.copy.CopyReadService;
import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.ApplicationStatus;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.service.ApplicationParticipantPort;
import io.agentflow.approval.service.TaskRecipientDirectory;
import io.agentflow.attachment.Attachment;
import io.agentflow.attachment.AttachmentService;
import io.agentflow.attachment.JdbcAttachmentRepository;
import io.agentflow.attachment.LocalAttachmentStore;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.agentflow.signature.SignatureVerificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实字段投影与冻结附件读取串联验证；角色、组织、资料和来源各自约束，不相互补权。
 * @author owlzhangfq@gmail.com
 */
class SignatureAccessTest {
    private final CurrentActor actors = new CurrentActor();
    private final ApprovalApplicationFacade applications = mock(ApprovalApplicationFacade.class);
    private final SubmissionRoundRepository rounds = mock(SubmissionRoundRepository.class);
    private final JdbcAttachmentRepository files = mock(JdbcAttachmentRepository.class);
    private final OrganizationRepository organization = mock(OrganizationRepository.class);
    private final TaskRecipientDirectory recipients = mock(TaskRecipientDirectory.class);
    private final ApplicationParticipantPort participants = mock(ApplicationParticipantPort.class);
    private final SignatureGatewayConfiguration configuration = new SignatureGatewayConfiguration();
    private final SignatureGatewayConfiguration.Profile profile = new SignatureGatewayConfiguration.Profile();
    private final UUID applicationId = UUID.randomUUID(), fileId = UUID.randomUUID();
    private final Actor alice = actor("alice", "EMPLOYEE"), manager = actor("manager", "APPROVER");
    private SignatureAccess access;
    private Application application;
    private Attachment original;

    @BeforeEach void setup() {
        var schema = new FormSchema(1, List.of(new FormSchema.Field("contract", "合同", FormSchema.FieldType.ATTACHMENT,
                false, null, null, null, null, null, null, null, true,
                Map.of("review", FieldVisibility.READ_ONLY, "masked", FieldVisibility.MASKED))));
        var payload = Map.<String, Object>of("contract", List.of(fileId.toString()));
        application = Application.restore(applicationId, "tenant-a", "contract-1", "contract", 3, "alice", "合同", payload, ApplicationStatus.APPROVED, 2, 7, schema);
        var round = new SubmissionRound("tenant-a", applicationId, 2, "original-process", 3, "合同", payload, "alice", NOW.minusSeconds(10),
                SubmissionRound.Status.APPROVED, null, "manager", NOW.minusSeconds(1), schema);
        when(rounds.findByRound("tenant-a", applicationId, 2)).thenReturn(Optional.of(round));
        when(applications.getForActor(any(), eq(applicationId))).thenAnswer(call -> {
            Actor actor = call.getArgument(0);
            if (!actor.tenantId().equals("tenant-a")) throw new DomainException("NOT_FOUND", "Application not found");
            return application;
        });
        original = new Attachment(fileId, "tenant-a", applicationId, "contract", "original.pdf", 100, "a".repeat(64), "alice", NOW, Attachment.Status.READY);
        when(files.get("tenant-a", applicationId, fileId)).thenReturn(original);
        when(files.frozen(original, 2)).thenReturn(true);
        when(recipients.eligible("tenant-a", "manager")).thenReturn(true);
        when(participants.readableNodes("tenant-a", "original-process", manager)).thenReturn(Set.of("review"));
        var fields = new ApplicationFieldViews(actors, List.of(participants), recipients, rounds);
        var attachments = new AttachmentService(applications, fields, actors, files, mock(LocalAttachmentStore.class),
                mock(PlatformTransactionManager.class), mock(CopyReadService.class));
        var trusted = profile(keyPair());
        profile.setKey(trusted.key()); profile.setVersion(trusted.version()); profile.setName(trusted.name());
        profile.setActors(List.of("alice", "manager", "admin")); profile.setSigners(trusted.signers()); profile.setReceiptPublicKey(trusted.receiptPublicKey());
        profile.setEndpoint("https://provider.example/signature"); profile.setToken("secret-credential");
        configuration.setEnabled(true); configuration.setTenants(Map.of("tenant-a", List.of(profile)));
        access = new SignatureAccess(applications, attachments, rounds, organization, configuration);
    }
    @AfterEach void clear() { actors.clear(); }

    @Test void approvedOriginalUsesExplicitActorEvenWhenThreadContainsAnotherAccount() {
        actors.set(actor("admin", "ADMIN"));
        var input = access.prepare(alice, applicationId, command(), NOW);
        assertThat(input.request().authorization().actor()).isEqualTo("alice");
        assertThat(input.request().documents()).containsExactly(new SignatureRequest.Document(fileId, fileId, "contract", "original.pdf", 100, "a".repeat(64)));
        assertThat(input.request().signers()).isEqualTo(profile.getSigners());
        assertThat(actors.actor().userId()).isEqualTo("admin");
        access.requireInitialSend(alice, SignatureOperation.queue(input, NOW));
    }

    @Test void administratorCannotReadHiddenOrMaskedFilesEvenWhenProfileExplicitlyAuthorizesThem() {
        var admin = actor("admin", "ADMIN", "APPROVER");
        assertThatThrownBy(() -> access.prepare(admin, applicationId, command(), NOW)).isInstanceOf(DomainException.class);
        when(recipients.eligible("tenant-a", "admin")).thenReturn(true);
        when(participants.readableNodes("tenant-a", "original-process", admin)).thenReturn(Set.of("masked"));
        assertThatThrownBy(() -> access.prepare(admin, applicationId, command(), NOW)).isInstanceOf(DomainException.class);
    }

    @Test void directoryEligibilityAndCurrentAuthenticationRolesAreBothRequiredForNodeFiles() {
        var queued = SignatureOperation.queue(access.prepare(manager, applicationId, command(), NOW), NOW);
        access.requireInitialSend(manager, queued);
        assertThatThrownBy(() -> access.requireInitialSend(actor("manager", "EMPLOYEE"), queued)).isInstanceOf(DomainException.class);
        when(recipients.eligible("tenant-a", "manager")).thenReturn(false);
        assertThatThrownBy(() -> access.requireInitialSend(manager, queued)).isInstanceOf(DomainException.class);
    }

    @Test void missingInactiveOrganizationPersonAndForeignTenantCannotAuthorize() {
        when(organization.initialized("tenant-a")).thenReturn(true);
        when(organization.personBySubject("tenant-a", "alice")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.prepare(alice, applicationId, command(), NOW)).isInstanceOf(DomainException.class);
        when(organization.personBySubject("tenant-a", "alice")).thenReturn(Optional.of(new OrganizationPerson(UUID.randomUUID(), "alice", "Alice", false, true, 1)));
        assertThatThrownBy(() -> access.prepare(alice, applicationId, command(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> access.prepare(new Actor("foreign", "alice", Set.of("ADMIN")), applicationId, command(), NOW)).isInstanceOf(DomainException.class);
    }

    @Test void changedFrozenReferenceProfileAndApplicationCannotBeSent() {
        var queued = SignatureOperation.queue(access.prepare(alice, applicationId, command(), NOW), NOW);
        profile.setEnabled(false);
        assertThatThrownBy(() -> access.requireInitialSend(alice, queued)).isInstanceOf(DomainException.class);
        // 停用资料不删除既有合法读取权。
        access.requireReadable(alice, queued);
        profile.setEnabled(true); profile.setEndpoint("https://replacement.example/signature");
        assertThatThrownBy(() -> access.requireInitialSend(alice, queued)).isInstanceOf(DomainException.class);
        profile.setEndpoint("https://provider.example/signature"); when(files.frozen(original, 2)).thenReturn(false);
        assertThatThrownBy(() -> access.requireInitialSend(alice, queued)).isInstanceOf(DomainException.class);
        when(files.frozen(original, 2)).thenReturn(true);
        application = Application.restore(applicationId, "tenant-a", "contract-1", "contract", 3, "alice", "合同", application.payload(), ApplicationStatus.CANCELLED, 2, 8, application.formSchema());
        assertThatThrownBy(() -> access.requireInitialSend(alice, queued)).isInstanceOf(DomainException.class);
    }

    @Test void changingMetadataCannotSubstituteOriginalAndUnlistedActorsCannotUseProfile() {
        var queued = SignatureOperation.queue(access.prepare(alice, applicationId, command(), NOW), NOW);
        when(files.get("tenant-a", applicationId, fileId)).thenReturn(new Attachment(fileId, "tenant-a", applicationId, "contract", "replacement.pdf", 100, "a".repeat(64), "alice", NOW, Attachment.Status.READY));
        assertThatThrownBy(() -> access.requireReadable(alice, queued)).isInstanceOf(DomainException.class);
        when(files.get("tenant-a", applicationId, fileId)).thenReturn(original); profile.setActors(List.of("manager"));
        assertThatThrownBy(() -> access.prepare(alice, applicationId, command(), NOW)).isInstanceOf(DomainException.class);
    }

    private SignatureAccess.CreateInput command() { return new SignatureAccess.CreateInput(2, 7, profile.getKey(), profile.getVersion(), List.of(fileId), "授权合同签署", NOW.plusSeconds(300)); }
    private static Actor actor(String user, String... roles) { return new Actor("tenant-a", user, Set.of(roles)); }
}
