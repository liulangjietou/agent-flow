package io.agentflow.template;

import io.agentflow.definition.DefinitionApplicationService;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 模板复制用例编排定义创建与出处保存，目录始终作为不可变输入。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProcessTemplateApplicationService {
    private final ClasspathProcessTemplateCatalog catalog;
    private final DefinitionApplicationService definitions;
    private final TemplateCopyRepository copies;

    /** 创建模板复制服务。 */
    public ProcessTemplateApplicationService(ClasspathProcessTemplateCatalog catalog,
                                              DefinitionApplicationService definitions, TemplateCopyRepository copies) {
        this.catalog = catalog;
        this.definitions = definitions;
        this.copies = copies;
    }

    /** 在同一事务中创建独立草稿及来源；不发布或启动流程。 */
    @Transactional
    public DefinitionDraft copy(String tenantId, String copiedBy, String templateKey, long templateVersion,
                                String processKey, String name) {
        ProcessTemplate template = catalog.requireVersion(templateKey, templateVersion);
        DefinitionDraft draft = definitions.create(tenantId, processKey, name, template.graph(), template.formSchema(), template.copiedNotificationTexts());
        copies.save(new TemplateCopy(tenantId, draft.id(), template.key(), template.templateVersion(), copiedBy, Instant.now()));
        return draft;
    }
}
