package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;

import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一个引擎等待执行对应一个不可改写命令；查询和重试始终携带同一身份、契约及输入。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskCommand(UUID id, String tenantId, Binding binding, ServiceTaskContract contract, Map<String, Object> inputs) {
    static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final int MAX_TENANT_LENGTH = 64;
    private static final int MAX_IDENTIFIER_LENGTH = 255;

    /** 唯一的输入冻结入口；调用方只能传入已经按节点权限选出的显式字段。 */
    public ServiceTaskCommand {
        if (id == null || !literal(tenantId, MAX_TENANT_LENGTH) || binding == null || contract == null) throw invalid();
        inputs = contract.freezeInputs(inputs);
    }

    /** 完整来源及带类型的参数参与摘要，不依赖 JSON 顺序或字符串拼接分隔符。 */
    public String digest() {
        var digest = ServiceTaskContract.sha256();
        ServiceTaskContract.add(digest, "agentflow-service-command-1", id.toString(), tenantId, binding.applicationId().toString(),
                Integer.toString(binding.roundNo()), binding.processKey(), Long.toString(binding.definitionVersion()), binding.definitionDigest(),
                binding.processInstanceId(), binding.executionId(), binding.nodeId(), contract.digest(), Integer.toString(inputs.size()));
        for (var parameter : contract.parameters()) {
            if (inputs.containsKey(parameter.name())) {
                ServiceTaskContract.add(digest, parameter.name(), parameter.type().name(), inputs.get(parameter.name()).toString());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 避免异常或诊断日志通过默认 record 文本泄露原始表单参数。 */
    @Override
    public String toString() {
        return "ServiceTaskCommand[id=" + id + ", tenantId=" + tenantId + ", applicationId=" + binding.applicationId()
                + ", roundNo=" + binding.roundNo() + ", operation=" + contract.key() + ", version=" + contract.version() + "]";
    }

    /**
     * 来源精确到定义版本和引擎 execution，不能用同名节点或新轮次替代原等待。
     * @author owlzhangfq@gmail.com
     */
    public record Binding(UUID applicationId, int roundNo, String processKey, long definitionVersion, String definitionDigest,
                          String processInstanceId, String executionId, String nodeId) {
        public Binding {
            if (applicationId == null || roundNo < 1 || definitionVersion < 1 || definitionDigest == null
                    || !DIGEST.matcher(definitionDigest).matches() || !literal(processKey, MAX_IDENTIFIER_LENGTH)
                    || !literal(processInstanceId, MAX_IDENTIFIER_LENGTH) || !literal(executionId, MAX_IDENTIFIER_LENGTH)
                    || !literal(nodeId, MAX_IDENTIFIER_LENGTH)) throw invalid();
        }
    }

    private static boolean literal(String value, int maximum) {
        return !StringUtils.isBlank(value) && value.length() <= maximum && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl) && ServiceTaskContract.unicode(value)
                && !value.contains("${") && !value.contains("#{");
    }

    private static DomainException invalid() { return new DomainException("INVALID_SERVICE_TASK_COMMAND", "Service task command requires its original tenant, round, definition and execution binding"); }
}
