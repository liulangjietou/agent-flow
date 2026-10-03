package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.servicetask.ServiceTaskCommand;
import io.agentflow.servicetask.ServiceTaskContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 发布时绑定来源权限与可信参数声明，运行时只从原表单投影这些字段，不输出整个表单上下文。
 * @author owlzhangfq@gmail.com
 */
public final class ServiceTaskInputs {
    private final String nodeId;
    private final ServiceTaskContract contract;
    private final List<Binding> bindings;

    private ServiceTaskInputs(String nodeId, ServiceTaskContract contract, List<Binding> bindings) {
        this.nodeId = nodeId; this.contract = contract; this.bindings = List.copyOf(bindings);
    }

    /** 目录由应用服务读取，纯领域绑定只信精确版本及摘要；缺省脱敏不能因服务调用而绕过。 */
    public static ServiceTaskInputs bind(ServiceTaskPolicy policy, String nodeId, FormSchema schema, ServiceTaskContract contract) {
        if (!policy.operationKey().equals(contract.key()) || policy.operationVersion() != contract.version() || !policy.contractDigest().equals(contract.digest())) {
            throw invalid("SERVICE_TASK_CONTRACT_MISMATCH", "Service task contract no longer matches the published reference");
        }
        var fields = schema == null ? Map.<String, FormSchema.Field>of() : schema.fields().stream().collect(Collectors.toMap(FormSchema.Field::key, field -> field));
        var parameters = contract.parameters().stream().collect(Collectors.toMap(ServiceTaskContract.Parameter::name, parameter -> parameter));
        var bindings = new ArrayList<Binding>();
        policy.inputs().forEach((parameterName, fieldKey) -> {
            var field = fields.get(fieldKey); var parameter = parameters.get(parameterName);
            if (field == null || parameter == null) throw invalid("SERVICE_TASK_INPUT_UNKNOWN", "Service task mapping must name a declared parameter and form field");
            if (!compatible(field.type(), parameter.type())) throw invalid("SERVICE_TASK_INPUT_TYPE_MISMATCH", "Service task parameter and source field types must match");
            if (field.visibility(Set.of(nodeId)) != FieldVisibility.READ_ONLY) {
                throw invalid("SERVICE_TASK_INPUT_NOT_READABLE", "Hidden or masked fields cannot be passed to a service task");
            }
            if (field.restricted() && !parameter.sensitive()) {
                throw invalid("SERVICE_TASK_INPUT_SENSITIVITY_LOSS", "Service task parameters must retain the source field sensitivity");
            }
            bindings.add(new Binding(parameter.name(), field.key()));
        });
        if (contract.parameters().stream().anyMatch(parameter -> parameter.required() && !policy.inputs().containsKey(parameter.name()))) {
            throw invalid("SERVICE_TASK_REQUIRED_INPUT_MISSING", "Every required service task parameter needs an explicit field mapping");
        }
        return new ServiceTaskInputs(nodeId, contract, bindings);
    }

    /** 激活原等待时冻结命令；值类型和大小只在命令构造边界校验，未映射的字段不进入命令。 */
    public ServiceTaskCommand command(UUID id, String tenantId, ServiceTaskCommand.Binding origin, Map<String, Object> sourceValues) {
        if (!nodeId.equals(origin.nodeId())) throw invalid("SERVICE_TASK_NODE_MISMATCH", "Service task inputs belong to another node");
        var inputs = new LinkedHashMap<String, Object>();
        for (var binding : bindings) {
            if (sourceValues.containsKey(binding.fieldKey())) inputs.put(binding.parameterName(), sourceValues.get(binding.fieldKey()));
        }
        return new ServiceTaskCommand(id, tenantId, origin, contract, inputs);
    }

    private static boolean compatible(FormSchema.FieldType field, ServiceTaskContract.Type parameter) {
        return switch (parameter) {
            case TEXT -> field == FormSchema.FieldType.TEXT || field == FormSchema.FieldType.TEXTAREA || field == FormSchema.FieldType.SELECT;
            case NUMBER -> field == FormSchema.FieldType.NUMBER;
            case BOOLEAN -> field == FormSchema.FieldType.BOOLEAN;
            case DATE -> field == FormSchema.FieldType.DATE;
        };
    }

    private static DomainException invalid(String code, String message) { return new DomainException(code, message); }

    /** @author owlzhangfq@gmail.com */
    private record Binding(String parameterName, String fieldKey) { }
}
