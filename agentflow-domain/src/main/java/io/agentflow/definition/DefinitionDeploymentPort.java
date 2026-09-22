package io.agentflow.definition;

import static io.agentflow.definition.DefinitionModels.DefinitionDraft;

/**
 * 流程定义发布端口。领域层只描述发布结果，不依赖具体流程引擎。
 */
public interface DefinitionDeploymentPort {
    /** 将已经完成领域校验并分配版本的定义发布到流程运行时。 */
    DeploymentResult deploy(DefinitionDraft draft);

    /** 流程引擎中的发布结果。 */
    record DeploymentResult(String deploymentId, String processDefinitionId, String processDefinitionKey,
                            long version) { }
}
