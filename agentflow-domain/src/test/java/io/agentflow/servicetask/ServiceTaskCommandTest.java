package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 不同租户、轮次、定义、引擎等待和输入必须形成不同的完整命令身份。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskCommandTest {
    @Test
    void inputOrderingDoesNotChangeDigestAndCommandLoggingDoesNotExposeValues() {
        var original = command();
        var reverse = new LinkedHashMap<String, Object>();
        reverse.put("note", "private-note"); reverse.put("approved", true); reverse.put("amount", "12.50");
        var reordered = new ServiceTaskCommand(original.id(), original.tenantId(), original.binding(), original.contract(), reverse);
        assertThat(original.digest()).isEqualTo(reordered.digest());
        // 独立 Python 按协议的 UTF-8 字节长度编码计算，避免生产端与测试端共享摘要实现。
        assertThat(original.contract().digest()).isEqualTo("d8a572f4efa5657b21b209f18e380eb54e16baa115c7aa8c26e86aa076a05044");
        assertThat(original.digest()).isEqualTo("9715b869c23f850fe48dd0f5888f9ca4a184796475fd2317993be176433d4312");
        assertThat(original.toString()).contains(original.id().toString()).doesNotContain("private-note", "12.50");
        reverse.put("note", "edited");
        assertThat(reordered.inputs()).containsEntry("note", "private-note");
    }

    @Test
    void everySourceIdentityAndVersionContributesToDigest() {
        var command = command(); var b = command.binding();
        var bindings = List.of(
                new ServiceTaskCommand.Binding(UUID.randomUUID(), b.roundNo(), b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), 2, b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), "another-process", b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), b.processKey(), 4, b.definitionDigest(), b.processInstanceId(), b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), b.processKey(), b.definitionVersion(), "b".repeat(64), b.processInstanceId(), b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), b.processKey(), b.definitionVersion(), b.definitionDigest(), "another-instance", b.executionId(), b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), "another-execution", b.nodeId()),
                new ServiceTaskCommand.Binding(b.applicationId(), b.roundNo(), b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), b.executionId(), "another-node"));
        for (var binding : bindings) assertThat(new ServiceTaskCommand(command.id(), command.tenantId(), binding, command.contract(), command.inputs()).digest())
                .isNotEqualTo(command.digest());
        assertThat(new ServiceTaskCommand(command.id(), "other-tenant", b, command.contract(), command.inputs()).digest()).isNotEqualTo(command.digest());
        assertThat(new ServiceTaskCommand(UUID.randomUUID(), command.tenantId(), b, command.contract(), command.inputs()).digest()).isNotEqualTo(command.digest());
        assertThat(new ServiceTaskCommand(command.id(), command.tenantId(), b, new ServiceTaskContract(command.contract().key(), 3, command.contract().name(), command.contract().parameters()), command.inputs()).digest())
                .isNotEqualTo(command.digest());
        assertThat(new ServiceTaskCommand(command.id(), command.tenantId(), b, command.contract(), Map.of("amount", "12.50", "approved", false, "note", "private-note")).digest())
                .isNotEqualTo(command.digest());
    }

    @Test
    void invalidRuntimeIdentityAndTargetDigestAreRejectedAtConstruction() {
        var command = command(); var b = command.binding();
        assertThatThrownBy(() -> new ServiceTaskCommand(command.id(), " tenant", b, command.contract(), command.inputs())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskCommand.Binding(b.applicationId(), 0, b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), b.executionId(), b.nodeId()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskCommand.Binding(b.applicationId(), 1, b.processKey(), b.definitionVersion(), b.definitionDigest(), b.processInstanceId(), "${bean.call()}", b.nodeId()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ServiceTaskOperation.Input(command, "https://example.invalid")).isInstanceOf(DomainException.class);
    }

    static ServiceTaskCommand command() {
        var contract = new ServiceTaskContract("receipt.register", 2, "登记凭据", List.of(
                new ServiceTaskContract.Parameter("amount", ServiceTaskContract.Type.NUMBER, true, false),
                new ServiceTaskContract.Parameter("approved", ServiceTaskContract.Type.BOOLEAN, false, false),
                new ServiceTaskContract.Parameter("note", ServiceTaskContract.Type.TEXT, false, true)));
        var binding = new ServiceTaskCommand.Binding(UUID.fromString("22222222-2222-2222-2222-222222222222"), 1, "request", 3,
                "a".repeat(64), "process-1", "execution-1", "register");
        return new ServiceTaskCommand(UUID.fromString("11111111-1111-1111-1111-111111111111"), "tenant", binding, contract,
                Map.of("amount", "12.50", "approved", true, "note", "private-note"));
    }
}
