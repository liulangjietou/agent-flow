package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.agentflow.servicetask.ServiceTaskContract.Type.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约冻结只接受有界标量，防止设计或恢复阶段隐式扩展外发内容。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskContractTest {
    @Test
    void acceptsOnlyDeclaredTypedInputsAndFreezesBothSchemaAndValues() {
        var parameters = new ArrayList<>(List.of(parameter("amount", NUMBER, true), parameter("agreed", BOOLEAN, false)));
        var contract = contract(parameters);
        parameters.clear();
        var values = new HashMap<String, Object>(Map.of("amount", "12.50", "agreed", true));
        var frozen = contract.freezeInputs(values);
        values.put("amount", "99");
        assertThat(frozen).containsEntry("amount", "12.50").containsEntry("agreed", true);
        assertThatThrownBy(() -> frozen.put("amount", "1")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> contract.parameters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(contract.freezeInputs(Map.of("amount", "12.50"))).hasSize(1);
        for (var invalid : List.of(Map.<String, Object>of(), Map.<String, Object>of("amount", 12.50),
                Map.<String, Object>of("amount", "12.50", "agreed", "true"), Map.<String, Object>of("amount", "12.50", "extra", "value"))) {
            assertThatThrownBy(() -> contract.freezeInputs(invalid)).isInstanceOf(DomainException.class);
        }
        var nullable = new HashMap<String, Object>(Map.of("amount", "12.50")); nullable.put("agreed", null);
        assertThatThrownBy(() -> contract.freezeInputs(nullable)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e3", " 12", "NaN", "+1", "", "0.0000000000000000000000000000000000001"})
    void rejectsNoncanonicalNumberInputs(String value) {
        assertThatThrownBy(() -> contract(List.of(parameter("amount", NUMBER, true))).freezeInputs(Map.of("amount", value)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void datesRequireActualCalendarDatesAndNoImplicitObjectConversion() {
        var contract = contract(List.of(parameter("date", DATE, true)));
        assertThat(contract.freezeInputs(Map.of("date", "2024-02-29"))).containsEntry("date", "2024-02-29");
        for (Object value : List.of("2025-02-29", "0000-01-01", "2024-2-29", java.time.LocalDate.of(2024, 2, 29))) {
            assertThatThrownBy(() -> contract.freezeInputs(Map.of("date", value))).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void enforcesUtf8PerValueAndTotalLimitsWithoutAcceptingStructuredOrMalformedUnicodeInputs() {
        var contract = contract(List.of(parameter("note", TEXT, true)));
        assertThat(contract.freezeInputs(Map.of("note", "文".repeat(2730)))).hasSize(1);
        for (Object value : List.of("文".repeat(2731), "\uD800", " ", List.of("secret"), Map.of("secret", "value"))) {
            assertThatThrownBy(() -> contract.freezeInputs(Map.of("note", value))).isInstanceOf(DomainException.class);
        }
        var parameters = new ArrayList<ServiceTaskContract.Parameter>();
        var values = new HashMap<String, Object>();
        for (int index = 0; index < 4; index++) {
            parameters.add(parameter("p" + index, TEXT, true)); values.put("p" + index, "x".repeat(8192));
        }
        var many = contract(parameters);
        assertThatThrownBy(() -> many.freezeInputs(values)).isInstanceOf(DomainException.class);
    }

    @Test
    void contractIdentityIsVersionedAndCanonicalWhileSemanticChangesAlterDigest() {
        var first = contract(List.of(parameter("b", BOOLEAN, false), parameter("a", TEXT, true)));
        var reordered = contract(List.of(parameter("a", TEXT, true), parameter("b", BOOLEAN, false)));
        assertThat(first.digest()).isEqualTo(reordered.digest());
        assertThat(first.parameters()).extracting(ServiceTaskContract.Parameter::name).containsExactly("a", "b");
        assertThat(first.digest()).isNotEqualTo(new ServiceTaskContract(first.key(), 2, first.name(), first.parameters()).digest())
                .isNotEqualTo(contract(List.of(parameter("a", TEXT, false), parameter("b", BOOLEAN, false))).digest())
                .isNotEqualTo(contract(List.of(parameter("a", DATE, true), parameter("b", BOOLEAN, false))).digest());
    }

    @Test
    void rejectsExecutableReferencesDuplicateParametersAndUnboundedDeclarations() {
        for (String key : List.of("https://example.invalid", "${bean.run()}", "#{script}", " key", "", "a".repeat(65))) {
            assertThatThrownBy(() -> new ServiceTaskContract(key, 1, "name", List.of())).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new ServiceTaskContract("valid", 0, "name", List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> contract(List.of(parameter("a", TEXT, true), parameter("a", TEXT, false)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> contract(java.util.stream.IntStream.range(0, 17).mapToObj(i -> parameter("p" + i, TEXT, false)).toList()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskContract("valid", 1, "\uD800", List.of())).isInstanceOf(DomainException.class);
    }

    private ServiceTaskContract contract(List<ServiceTaskContract.Parameter> parameters) { return new ServiceTaskContract("receipt.register", 1, "登记凭据", parameters); }
    private ServiceTaskContract.Parameter parameter(String name, ServiceTaskContract.Type type, boolean required) { return new ServiceTaskContract.Parameter(name, type, required, false); }
}
