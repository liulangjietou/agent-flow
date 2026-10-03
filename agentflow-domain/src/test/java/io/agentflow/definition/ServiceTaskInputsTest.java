package io.agentflow.definition;

import io.agentflow.common.DomainException;
import io.agentflow.form.FieldVisibility;
import io.agentflow.form.FormSchema;
import io.agentflow.servicetask.ServiceTaskCommand;
import io.agentflow.servicetask.ServiceTaskContract;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读服务也必须遵守原节点字段权限，敏感输入须由来源和接收契约同时明确允许。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskInputsTest {
    @Test
    void mapsOnlyExplicitAuthorizedFieldsIntoTheOriginalExecutionCommand() {
        var contract = contract(true);
        var bound = ServiceTaskInputs.bind(policy(contract, Map.of("memo", "reason")), "service", schema(true, FieldVisibility.READ_ONLY), contract);
        var source = new HashMap<String, Object>(Map.of("reason", "restricted-value", "unmapped", "never-send", "tenantId", "other-tenant"));
        var command = bound.command(UUID.randomUUID(), "tenant", origin("service"), source);
        source.put("reason", "later-value");
        assertThat(command.inputs()).containsExactlyEntriesOf(Map.of("memo", "restricted-value"));
        assertThat(command.tenantId()).isEqualTo("tenant");
        assertThatThrownBy(() -> bound.command(UUID.randomUUID(), "tenant", origin("other"), source))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_NODE_MISMATCH"));
    }

    @Test
    void maskedHiddenAndDefaultSensitiveFieldsCannotBeExported() {
        var contract = contract(true); var policy = policy(contract, Map.of("memo", "reason"));
        for (var visibility : List.of(FieldVisibility.MASKED, FieldVisibility.HIDDEN)) {
            assertThatThrownBy(() -> ServiceTaskInputs.bind(policy, "service", schema(true, visibility), contract))
                    .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_INPUT_NOT_READABLE"));
        }
        assertThatThrownBy(() -> ServiceTaskInputs.bind(policy, "service", schema(true, null), contract))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_INPUT_NOT_READABLE"));
    }

    @Test
    void explicitNodeReadabilityDoesNotDowngradeSensitiveDataAtTheReceivingContract() {
        var contract = contract(false);
        assertThatThrownBy(() -> ServiceTaskInputs.bind(policy(contract, Map.of("memo", "reason")), "service", schema(true, FieldVisibility.READ_ONLY), contract))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_INPUT_SENSITIVITY_LOSS"));
        var restrictedElsewhere = new FormSchema(2, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, false, Map.of("other", FieldVisibility.HIDDEN))));
        assertThatThrownBy(() -> ServiceTaskInputs.bind(policy(contract, Map.of("memo", "reason")), "service", restrictedElsewhere, contract))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_INPUT_SENSITIVITY_LOSS"));
    }

    @Test
    void unknownFieldsMissingRequiredInputsAndChangedContractsAreRejected() {
        var contract = contract(false); var schema = schema(false, null);
        for (var mapping : List.of(Map.of("memo", "missing"), Map.of("unknown", "reason"), Map.<String, String>of())) {
            assertThatThrownBy(() -> ServiceTaskInputs.bind(policy(contract, mapping), "service", schema, contract)).isInstanceOf(DomainException.class);
        }
        var policy = policy(contract, Map.of("memo", "reason"));
        var changed = new ServiceTaskContract(contract.key(), contract.version(), "changed name", contract.parameters());
        assertThatThrownBy(() -> ServiceTaskInputs.bind(policy, "service", schema, changed))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_CONTRACT_MISMATCH"));
        var amount = new ServiceTaskContract("receipt.register", 1, "登记", List.of(new ServiceTaskContract.Parameter("memo", ServiceTaskContract.Type.NUMBER, true, false)));
        assertThatThrownBy(() -> ServiceTaskInputs.bind(policy(amount, Map.of("memo", "reason")), "service", schema, amount))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.code()).isEqualTo("SERVICE_TASK_INPUT_TYPE_MISMATCH"));
    }

    @Test
    void nodePropertiesRejectLatestVersionsExpressionsUnknownExecutionSettingsAndNestedMappings() {
        var contract = contract(false);
        var properties = Map.of(ServiceTaskPolicy.KEY_PROPERTY, contract.key(), ServiceTaskPolicy.VERSION_PROPERTY, "1",
                ServiceTaskPolicy.DIGEST_PROPERTY, contract.digest(), "serviceInput.memo", "reason");
        assertThat(ServiceTaskPolicy.fromProperties(properties)).isEqualTo(policy(contract, Map.of("memo", "reason")));
        for (String version : List.of("latest", "01", "0", "-1", "9223372036854775808", "${version}")) {
            var invalid = new HashMap<>(properties); invalid.put(ServiceTaskPolicy.VERSION_PROPERTY, version);
            assertThatThrownBy(() -> ServiceTaskPolicy.fromProperties(invalid)).isInstanceOf(DomainException.class);
        }
        for (String key : List.of("url", "bean", "script", "resultVariable", "serviceInput.lines[0]")) {
            var invalid = new HashMap<>(properties); invalid.put(key, "reason");
            assertThatThrownBy(() -> ServiceTaskPolicy.fromProperties(invalid)).isInstanceOf(DomainException.class);
        }
        var expression = new HashMap<>(properties); expression.put("serviceInput.memo", "${formData.reason}");
        assertThatThrownBy(() -> ServiceTaskPolicy.fromProperties(expression)).isInstanceOf(DomainException.class);
    }

    private ServiceTaskContract contract(boolean sensitive) {
        return new ServiceTaskContract("receipt.register", 1, "登记", List.of(new ServiceTaskContract.Parameter("memo", ServiceTaskContract.Type.TEXT, true, sensitive)));
    }
    private ServiceTaskPolicy policy(ServiceTaskContract contract, Map<String, String> inputs) { return new ServiceTaskPolicy(contract.key(), contract.version(), contract.digest(), inputs); }
    private FormSchema schema(boolean sensitive, FieldVisibility visibility) {
        return new FormSchema(2, List.of(new FormSchema.Field("reason", "说明", FormSchema.FieldType.TEXT, true,
                null, null, null, null, null, null, null, sensitive, visibility == null ? null : Map.of("service", visibility))));
    }
    private ServiceTaskCommand.Binding origin(String nodeId) { return new ServiceTaskCommand.Binding(UUID.randomUUID(), 1, "request", 1, "a".repeat(64), "process-1", "execution-1", nodeId); }
}
