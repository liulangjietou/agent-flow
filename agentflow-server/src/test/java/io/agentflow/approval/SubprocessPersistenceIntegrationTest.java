package io.agentflow.approval;

import io.agentflow.approval.model.Application;
import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.approval.model.SubprocessCall;
import io.agentflow.approval.repository.ApplicationRepository;
import io.agentflow.approval.repository.SubmissionRoundRepository;
import io.agentflow.approval.repository.SubprocessCallRepository;
import io.agentflow.attachment.Attachment;
import io.agentflow.attachment.AttachmentReferences;
import io.agentflow.attachment.AttachmentService;
import io.agentflow.attachment.JdbcAttachmentRepository;
import io.agentflow.attachment.LocalAttachmentStore;
import io.agentflow.attachment.SubprocessAttachmentService;
import io.agentflow.common.Actor;
import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionDraftRepository;
import io.agentflow.definition.SubprocessInputs;
import io.agentflow.definition.SubprocessPolicy;
import io.agentflow.form.FormSchema;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.agentflow.definition.DefinitionModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 关系和附件使用真实持久化事务；本夹具尚不启动原生父子流程，不充当完整审批验收。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"agentflow.auth.demo-enabled=true", "agentflow.attachments.max-file-bytes=1024",
        "agentflow.attachments.max-application-bytes=4096", "agentflow.attachments.max-application-uploads=3"})
class SubprocessPersistenceIntegrationTest {
    static final Path DIRECTORY = Path.of("/fyoung/tmp/agentflow-subprocess-files-" + UUID.randomUUID());
    static final byte[] CONTENT = {1, 3, 5, 7};
    static final String SYSTEM = "system:subprocess";
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRoundRepository rounds;
    @Autowired SubprocessCallRepository calls;
    @Autowired DefinitionDraftRepository definitions;
    @Autowired JdbcAttachmentRepository files;
    @Autowired LocalAttachmentStore store;
    @Autowired SubprocessAttachmentService attachments;
    @Autowired AttachmentService downloads;
    @Autowired CurrentActor actors;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("agentflow.attachments.directory", DIRECTORY::toString);
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_URL", "jdbc:h2:mem:subprocess-persistence;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"));
        registry.add("spring.datasource.driver-class-name", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_DRIVER", "org.h2.Driver"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_USER", "sa"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AGENTFLOW_SUBPROCESS_PERSISTENCE_PASSWORD", ""));
    }

    @Test
    void storesChildRoundBeforeParentRoundAndRebindsRepeatedDetailFilesWithoutCopyingBytes() throws Exception {
        var fixture = fixture(); var childId = UUID.randomUUID();
        var call = invoke(fixture, childId, "activation");
        assertThat(rounds.findAll("demo", fixture.parent().id())).isEmpty();
        assertThat(calls.findByChild("demo", childId)).contains(call);
        assertThat(calls.findByActivation("demo", call.parentProcessInstanceId(), "activation")).contains(call);
        assertThat(calls.findByParentRound("demo", fixture.parent().id(), 1)).containsExactly(call);
        assertThat(calls.findByParentRound("demo", fixture.parent().id(), 2)).isEmpty();
        assertThat(calls.findByChild("foreign", childId)).isEmpty();
        var child = applications.findById("demo", childId).orElseThrow();
        var snapshot = rounds.findByRound("demo", childId, 1).orElseThrow();
        assertThat(snapshot.payload()).isEqualTo(child.payload());
        assertThat(snapshot.submittedBy()).isEqualTo(SYSTEM);
        var references = AttachmentReferences.collect(child.formSchema(), child.payload());
        assertThat(references).hasSize(2);
        for (var reference : references) {
            var file = files.get("demo", childId, reference.id());
            assertThat(file.fieldPath()).isEqualTo(reference.fieldPath());
            assertThat(file.id()).isNotEqualTo(file.contentId());
            assertThat(files.frozen(file, 1)).isTrue();
            assertThat(store.read(file)).isEqualTo(CONTENT);
            failure(() -> files.get("demo", fixture.parent().id(), file.id()), "NOT_FOUND");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stored_document_inventory WHERE id=?", Integer.class, file.id().toString())).isZero();
            actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
            try {
                assertThat(downloads.download(childId, file.id(), 1).content()).isEqualTo(CONTENT);
                failure(() -> downloads.download(childId, file.contentId(), 1), "NOT_FOUND");
            } finally { actors.clear(); }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_subprocess_attachment WHERE call_id=?", Integer.class, call.id().toString())).isEqualTo(2);
        assertThat(Files.exists(DIRECTORY.resolve(fixture.top().id() + ".bin"))).isTrue();
        for (var reference : references) assertThat(Files.exists(DIRECTORY.resolve(reference.id() + ".bin"))).isFalse();
    }

    @Test
    void duplicateActivationRollsBackTheSecondChildApplicationAndRound() throws Exception {
        var fixture = fixture(); var first = invoke(fixture, UUID.randomUUID(), "same-activation");
        var duplicateId = UUID.randomUUID();
        failure(() -> invoke(fixture, duplicateId, "same-activation"), "SUBPROCESS_CALL_EXISTS");
        assertAbsent(duplicateId);
        assertThat(calls.findByParentRound("demo", fixture.parent().id(), 1)).containsExactly(first);
    }

    @Test
    void aLaterFailureRollsBackRelationAndAliasesButLeavesBothOriginalFilesUntouched() throws Exception {
        var fixture = fixture(); var childId = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            invoke(fixture, childId, "rolled-back");
            throw new IllegalStateException("Injected failure after attachment references were frozen");
        })).isInstanceOf(IllegalStateException.class);
        assertAbsent(childId);
        assertThat(store.read(fixture.top())).isEqualTo(CONTENT);
        assertThat(store.read(fixture.detail())).isEqualTo(CONTENT);
        assertThat(calls.findByParentRound("demo", fixture.parent().id(), 1)).isEmpty();
    }

    @Test
    void childQuotaCountsLogicalReferencesAndFailureRollsBackPreviouslyInsertedAliases() throws Exception {
        var fixture = fixture(); var childId = UUID.randomUUID();
        var schema = schema(file("a"), file("b"), file("c"), file("d"));
        var definition = definition(schema);
        var policy = new SubprocessPolicy(definition.key(), 1, Map.of("a", "proof", "b", "proof", "c", "proof", "d", "proof"));
        var overflowing = new Fixture(fixture.parent(), definition, policy, fixture.top(), fixture.detail());
        failure(() -> invoke(overflowing, childId, "over-quota"), "ATTACHMENT_QUOTA_EXCEEDED");
        assertAbsent(childId);
        assertThat(store.read(fixture.top())).isEqualTo(CONTENT);
    }

    @Test
    void refusesWrongFieldForeignApplicationUnreadyAndTamperedOriginalsBeforeCreatingAliases() throws Exception {
        var fixture = fixture();
        var projection = project(fixture);
        var wrongField = new SubprocessInputs.Projection(projection.values(), List.of(
                new SubprocessInputs.AttachmentInput("lines.proof", "document", fixture.top().id())));
        failure(() -> prepare(fixture, wrongField), "INVALID_ATTACHMENT_REFERENCE");
        var foreign = fixture();
        var wrongOwner = new SubprocessInputs.Projection(projection.values(), List.of(
                new SubprocessInputs.AttachmentInput("proof", "document", foreign.top().id())));
        failure(() -> prepare(fixture, wrongOwner), "INVALID_ATTACHMENT_REFERENCE");
        jdbc.update("UPDATE approval_attachment SET status='UPLOADING' WHERE id=?", fixture.top().id().toString());
        failure(() -> prepare(fixture, projection), "ATTACHMENT_NOT_READY");
        jdbc.update("UPDATE approval_attachment SET status='READY' WHERE id=?", fixture.top().id().toString());
        Files.write(DIRECTORY.resolve(fixture.top().id() + ".bin"), new byte[]{0, 0, 0, 0});
        failure(() -> prepare(fixture, projection), "ATTACHMENT_INTEGRITY_FAILED");
        assertThat(calls.findByParentRound("demo", fixture.parent().id(), 1)).isEmpty();
    }

    @Test
    void databaseRejectsCrossTenantReferencesAndChangedOriginalFingerprints() throws Exception {
        var fixture = fixture(); var call = invoke(fixture, UUID.randomUUID(), "identity");
        String alias = jdbc.queryForObject("SELECT target_attachment_id FROM approval_subprocess_attachment WHERE call_id=? AND target_field_path='document'", String.class, call.id().toString());
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_attachment SET sha256=? WHERE id=?", "f".repeat(64), alias)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_subprocess_call SET child_definition_version=2 WHERE id=?", call.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_subprocess_call SET child_instance_id='wrong-instance' WHERE id=?", call.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_subprocess_call SET tenant_id='foreign' WHERE id=?", call.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE approval_subprocess_attachment SET source_field_path='document' WHERE call_id=?", call.id().toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM approval_attachment WHERE id=?", fixture.top().id().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preparedInputCannotBePersistedForAnotherParentChildPairAndRequiresATransaction() throws Exception {
        var fixture = fixture(); var childId = UUID.randomUUID(); var call = invoke(fixture, childId, "bound");
        var prepared = new TransactionTemplate(transactions).execute(status -> attachments.prepare(fixture.parent(), UUID.randomUUID(),
                fixture.definition().formSchema(), project(fixture), SYSTEM, Instant.now()));
        failure(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> attachments.persist(call, prepared)), "INVALID_ATTACHMENT_REFERENCE");
        assertThatThrownBy(() -> attachments.prepare(fixture.parent(), UUID.randomUUID(), fixture.definition().formSchema(), project(fixture), SYSTEM, Instant.now()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> calls.append(call)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void sharedReferencesUseChildFieldPermissionsForAdministratorsAndOtherReaders() throws Exception {
        var source = fixture();
        var protectedFile = new FormSchema.Field("document", "受控材料", FormSchema.FieldType.ATTACHMENT, true,
                null, null, null, null, null, null, null, true, Map.of("childReview", io.agentflow.form.FieldVisibility.READ_ONLY));
        var definition = definition(schema(protectedFile));
        var fixture = new Fixture(source.parent(), definition, new SubprocessPolicy(definition.key(), 1, Map.of("document", "proof")), source.top(), source.detail());
        var call = invoke(fixture, UUID.randomUUID(), "protected-child");
        var child = applications.findById("demo", call.childApplicationId()).orElseThrow();
        var reference = AttachmentReferences.collect(child.formSchema(), child.payload()).iterator().next();
        try {
            actors.set(new Actor("demo", "alice", Set.of("EMPLOYEE")));
            assertThat(downloads.download(child.id(), reference.id(), 1).content()).isEqualTo(CONTENT);
            actors.set(new Actor("demo", "admin", Set.of("ADMIN")));
            failure(() -> downloads.download(child.id(), reference.id(), 1), "FORBIDDEN");
            actors.set(new Actor("demo", "bob", Set.of("EMPLOYEE")));
            failure(() -> downloads.download(child.id(), reference.id(), 1), "NOT_FOUND");
        } finally { actors.clear(); }
    }

    private SubprocessAttachmentService.Prepared prepare(Fixture fixture, SubprocessInputs.Projection projection) {
        return new TransactionTemplate(transactions).execute(status -> attachments.prepare(fixture.parent(), UUID.randomUUID(),
                fixture.definition().formSchema(), projection, SYSTEM, Instant.now()));
    }

    private SubprocessInputs.Projection project(Fixture fixture) {
        return SubprocessInputs.bind(fixture.policy(), "call", fixture.parent().formSchema(), fixture.definition().formSchema()).project(fixture.parent().payload());
    }

    private SubprocessCall invoke(Fixture fixture, UUID childId, String activationId) {
        return new TransactionTemplate(transactions).execute(status -> {
            var parent = applications.lockById("demo", fixture.parent().id()).orElseThrow();
            var at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var prepared = attachments.prepare(parent, childId, fixture.definition().formSchema(), project(fixture), SYSTEM, at);
            var child = Application.draft(childId, "demo", "child-" + childId, fixture.definition().key(), 1, "alice", "子申请",
                    prepared.values(), fixture.definition().formSchema(), "native-child-definition");
            child.submit(1); applications.save(child);
            String instance = "child-" + childId;
            rounds.append(SubmissionRound.submitted(child, instance, SYSTEM, at, null));
            var call = new SubprocessCall(UUID.randomUUID(), "demo", parent.id(), 1, "parent-" + parent.id(), "native-parent-definition",
                    "call", "材料核对", activationId, childId, instance, fixture.definition().id(), "native-child-definition", fixture.policy(), at);
            calls.append(call); attachments.persist(call, prepared);
            return call;
        });
    }

    private Fixture fixture() throws Exception {
        var parentId = UUID.randomUUID(); var topId = UUID.randomUUID(); var detailId = UUID.randomUUID();
        var parentSchema = schema(file("proof"), table("lines"));
        var payload = Map.<String, Object>of("proof", List.of(topId.toString()), "lines", List.of(
                Map.of("proof", List.of(detailId.toString())), Map.of("proof", List.of(detailId.toString()))));
        var parent = Application.draft(parentId, "demo", "parent-" + parentId, "parent", 1, "alice", "父申请", payload, parentSchema, "native-parent-definition");
        applications.save(parent);
        var top = original(parentId, topId, "proof"); var detail = original(parentId, detailId, "lines.proof");
        var definition = definition(schema(file("document"), table("details")));
        return new Fixture(parent, definition, new SubprocessPolicy(definition.key(), 1, Map.of("document", "proof", "details", "lines")), top, detail);
    }

    private Attachment original(UUID app, UUID id, String field) throws Exception {
        var file = new Attachment(id, "demo", app, field, "证明.bin", CONTENT.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT)), "alice", Instant.now(), Attachment.Status.READY);
        files.insert(file); var staged = store.stage(file, new ByteArrayInputStream(CONTENT)); store.publish(file, staged); store.discard(staged);
        return file;
    }

    private DefinitionDraft definition(FormSchema schema) {
        var graph = new Graph(List.of(new Node("start", "发起", NodeType.START, Map.of()),
                new Node("end", "结束", NodeType.END, Map.of())), List.of(new Edge("next", "start", "end", null, false)));
        var definition = DefinitionDraft.create(UUID.randomUUID(), "demo", "child" + UUID.randomUUID().toString().replace("-", ""), "子流程", graph, schema);
        definitions.save(definition); definition.publish(0, 1); definitions.save(definition); return definition;
    }

    private void assertAbsent(UUID childId) {
        assertThat(applications.findById("demo", childId)).isEmpty();
        assertThat(rounds.findAll("demo", childId)).isEmpty();
        assertThat(calls.findByChild("demo", childId)).isEmpty();
        for (String table : List.of("approval_attachment", "approval_attachment_round")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE application_id=?", Integer.class, childId.toString())).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_subprocess_attachment WHERE child_application_id=?", Integer.class, childId.toString())).isZero();
    }
    private static FormSchema schema(FormSchema.Field... fields) { return new FormSchema(2, List.of(fields)); }
    private static FormSchema.Field file(String key) { return new FormSchema.Field(key, key, FormSchema.FieldType.ATTACHMENT, true, null, null, null, null, null); }
    private static FormSchema.Field table(String key) { return new FormSchema.Field(key, key, FormSchema.FieldType.TABLE, true, null, null, null, null, null, List.of(file("proof")), null); }
    private static void failure(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    /** @author owlzhangfq@gmail.com */
    private record Fixture(Application parent, DefinitionDraft definition, SubprocessPolicy policy, Attachment top, Attachment detail) { }
}
