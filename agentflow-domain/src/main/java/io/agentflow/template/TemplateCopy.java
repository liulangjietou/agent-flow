package io.agentflow.template;

import java.time.Instant;
import java.util.UUID;

/**
 * 复制出处事实，只记录来源，不将目录更新传播到已有流程定义。
 * @author owlzhangfq@gmail.com
 */
public record TemplateCopy(String tenantId, UUID definitionId, String templateKey, long templateVersion,
                           String copiedBy, Instant copiedAt) { }
