package io.agentflow.agent;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 绑定已授权的申请版本与输入证据摘要，不携带模型密钥或可执行的资源地址。
 * @author owlzhangfq@gmail.com
 */
public record AssistInput(UUID applicationId, long applicationVersion, int roundNo, List<Reference> references) {
    public static final int MAX_REFERENCES = 64;

    /** 输入引用由申请读取适配器生成，来源授权与字段发送策略在进入本上下文前完成。 */
    public AssistInput {
        Objects.requireNonNull(applicationId);
        if (applicationVersion < 1 || roundNo < 1 || CollectionUtils.isEmpty(references)
                || references.size() > MAX_REFERENCES) {
            throw invalid("Invalid approval summary input");
        }
        var ids = new HashSet<String>();
        for (Reference reference : references) {
            if (reference == null || !ids.add(reference.sourceId())) {
                throw invalid("Input source identifiers must be unique");
            }
        }
        references = List.copyOf(references);
    }

    /**
     * 服务端定义的源标识与 SHA-256 内容摘要；模型结果必须同时匹配两项。
     * @author owlzhangfq@gmail.com
     */
    public record Reference(String sourceId, String contentDigest) {
        private static final Pattern SOURCE_ID = Pattern.compile("(?:application|form):[A-Za-z][A-Za-z0-9_.\\[\\]-]{0,128}");
        private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

        /** 标识是输入清单中的不透明键，禁止被当成 URL、文件路径或工具调用执行。 */
        public Reference {
            if (StringUtils.isBlank(sourceId) || !SOURCE_ID.matcher(sourceId).matches()
                    || contentDigest == null || !SHA256.matcher(contentDigest).matches()) {
                throw invalid("Invalid input evidence reference");
            }
        }
    }

    private static DomainException invalid(String message) { return new DomainException("INVALID_AGENT_INPUT", message); }
}
