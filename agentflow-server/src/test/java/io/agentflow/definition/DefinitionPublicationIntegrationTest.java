package io.agentflow.definition;

import io.agentflow.auth.AuthService;
import io.agentflow.common.Actor;
import io.agentflow.common.JsonUtil;
import io.agentflow.template.ClasspathProcessTemplateCatalog;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实事务验证发布记录、权限、幂等重放、历史缺失与引擎回滚。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:definition-publication;DB_CLOSE_DELAY=-1", "agentflow.auth.demo-enabled=true"})
@AutoConfigureMockMvc
class DefinitionPublicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonUtil json;
    @Autowired AuthService auth;
    @Autowired DefinitionApplicationService service;
    @Autowired DefinitionDraftRepository definitions;
    @Autowired ClasspathProcessTemplateCatalog catalog;
    @Autowired RepositoryService engine;
    @Autowired io.agentflow.api.idempotency.IdempotencyExecutor idempotency;
    @Autowired io.agentflow.common.CurrentActor currentActor;
    @MockitoSpyBean JdbcDefinitionPublicationRepository publications;

    @Test
    void recordsServerIdentitySummaryAndReplaysWithoutOverwriting() throws Exception {
        var draft = draft("demo");
        String key = UUID.randomUUID().toString();
        String body = json.write(Map.of("changeNote", "首次发布\n启用请假审批。", "publishedBy", "forged", "authorizedRole", "forged"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path(draft) + "/publish?expectedRevision=0").header("Authorization", token("admin"))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        }
        mvc.perform(get(path(draft) + "/publication").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("recorded").value(true))
                .andExpect(jsonPath("publication.publishedBy").value("admin"))
                .andExpect(jsonPath("publication.authorizedRole").value("ADMIN"))
                .andExpect(jsonPath("publication.publishedAt").isNotEmpty())
                .andExpect(jsonPath("publication.changeNote").value("首次发布\n启用请假审批。"))
                .andExpect(jsonPath("publication.validation.nodeCount").value(draft.graph().nodes().size()))
                .andExpect(jsonPath("publication.validation.fieldCount").value(draft.formSchema().fields().size()))
                .andExpect(jsonPath("publication.validation.formBound").value(true))
                .andExpect(jsonPath("publication.validation.checks[3]").value("FORM_FIELD_TYPES"));
        var original = publications.findByDefinition("demo", draft.id()).orElseThrow();
        mvc.perform(post(path(draft) + "/publish?expectedRevision=0").header("Authorization", token("admin"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"changeNote\":\"另一说明\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(post(path(draft) + "/publish?expectedRevision=0").header("Authorization", token("employee"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(publications.findByDefinition("demo", draft.id())).contains(original);
        assertThat(engine.createProcessDefinitionQuery().processDefinitionKey(draft.key()).count()).isEqualTo(1);
    }

    @Test
    void invalidNotesNeverPublishOrCreateRecords() throws Exception {
        var draft = draft("demo");
        for (String body : new String[]{"{}", "{\"changeNote\":null}", "{\"changeNote\":\"  \"}", json.write(Map.of("changeNote", "说".repeat(2001)))}) {
            mvc.perform(post(path(draft) + "/publish?expectedRevision=0").header("Authorization", token("admin"))
                            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_PUBLICATION_NOTE"));
        }
        mvc.perform(post(path(draft) + "/publish?expectedRevision=0").header("Authorization", token("admin"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("INVALID_PUBLICATION_NOTE"));
        assertUnpublished(draft);
    }

    @Test
    void legacySuccessfulBodylessRequestRemainsReplayableWithoutInventingPublication() throws Exception {
        var legacy = draft("demo");
        legacy.publish(0, 1);
        definitions.save(legacy);
        String key = UUID.randomUUID().toString();
        var oldRequest = new org.springframework.mock.web.MockHttpServletRequest("POST", path(legacy) + "/publish");
        oldRequest.setQueryString("expectedRevision=0");
        oldRequest.addHeader("Idempotency-Key", key);
        currentActor.set(auth.login("demo", "admin", "demo").actor());
        try {
            idempotency.execute(new org.springframework.web.util.ContentCachingRequestWrapper(oldRequest),
                    org.springframework.http.HttpStatus.OK, () -> DefinitionController.DefinitionResponse.from(legacy));
        } finally { currentActor.clear(); }
        mvc.perform(post(path(legacy) + "/publish?expectedRevision=0").header("Authorization", token("admin"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("version").value(1));
        assertThat(publications.findByDefinition("demo", legacy.id())).isEmpty();
        assertThat(service.get("demo", legacy.id()).revision()).isEqualTo(1);
    }

    @Test
    void legacyAbsenceIsExplicitAndReadAccessIsTenantScoped() throws Exception {
        var legacy = draft("demo");
        legacy.publish(0, 1);
        definitions.save(legacy);
        mvc.perform(get(path(legacy) + "/publication").header("Authorization", token("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("recorded").value(false))
                .andExpect(content().json("{\"recorded\":false,\"publication\":null}"));
        mvc.perform(get(path(legacy) + "/publication")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(legacy) + "/publication").header("Authorization", token("employee"))).andExpect(status().isForbidden());
        var foreign = draft("foreign");
        mvc.perform(get(path(foreign) + "/publication").header("Authorization", token("admin")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"));
        var draft = draft("demo");
        mvc.perform(get(path(draft) + "/publication").header("Authorization", token("admin")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("DEFINITION_NOT_PUBLISHED"));
    }

    @Test
    void failedRecordWriteRollsBackDefinitionAndRealEngineDeployment() {
        var draft = draft("demo");
        doThrow(new IllegalStateException("Publication storage unavailable")).when(publications).save(any());
        assertThatThrownBy(() -> service.publish(admin(), draft.id(), 0, "首次发布")).isInstanceOf(IllegalStateException.class);
        assertUnpublished(draft);
        doCallRealMethod().when(publications).save(any());
        var published = service.publish(admin(), draft.id(), 0, "修复后重试发布");
        assertThat(published.version()).isEqualTo(1);
        assertThat(publications.findByDefinition("demo", draft.id()).orElseThrow().changeNote()).isEqualTo("修复后重试发布");
    }

    @Test
    void persistedPublicationCannotBeOverwritten() {
        var draft = draft("demo");
        service.publish(admin(), draft.id(), 0, "不可变说明");
        var original = publications.findByDefinition("demo", draft.id()).orElseThrow();
        assertThatThrownBy(() -> publications.save(original)).isInstanceOf(DuplicateKeyException.class);
        assertThat(publications.findByDefinition("demo", draft.id())).contains(original);
        assertThat(publications.findByDefinition("foreign", draft.id())).isEmpty();
    }

    private void assertUnpublished(DefinitionModels.DefinitionDraft draft) {
        var persisted = service.get("demo", draft.id());
        assertThat(persisted.status()).isEqualTo(DefinitionModels.DraftStatus.DRAFT);
        assertThat(persisted.revision()).isZero();
        assertThat(persisted.version()).isZero();
        assertThat(publications.findByDefinition("demo", draft.id())).isEmpty();
        assertThat(engine.createProcessDefinitionQuery().processDefinitionKey(draft.key()).count()).isZero();
        assertThat(engine.createDeploymentQuery().deploymentKey(draft.key()).count()).isZero();
    }
    private DefinitionModels.DefinitionDraft draft(String tenant) {
        var template = catalog.get("leave-request");
        return service.create(tenant, "publish-" + UUID.randomUUID(), "请假审批", template.graph(), template.formSchema());
    }
    private Actor admin() { return new Actor("demo", "admin", Set.of("ADMIN")); }
    private String path(DefinitionModels.DefinitionDraft draft) { return "/api/v1/process-definitions/" + draft.id(); }
    private String token(String user) { return "Bearer " + auth.login("demo", user, "demo").token(); }
}
