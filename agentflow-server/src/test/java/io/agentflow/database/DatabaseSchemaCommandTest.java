package io.agentflow.database;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线入口的拼写、参数和凭证边界，不能意外回退为服务器启动。
 * @author owlzhangfq@gmail.com
 */
class DatabaseSchemaCommandTest {
    @Test
    void refusesAmbiguousCommandsAndMissingCredentialsWithoutPrintingSecrets() {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        var environment = Map.of("AGENTFLOW_DATASOURCE_URL", "jdbc:h2:mem:unexpected",
                "AGENTFLOW_DATASOURCE_USERNAME", "private-user", "AGENTFLOW_DATASOURCE_PASSWORD", "private-secret");
        for (String[] args : new String[][] {{"--schema=clean"}, {"--schema=migrate", "--server.port=8080"},
                {"--schema"}, {"--schema=validate"}}) {
            assertThat(DatabaseSchemaCommand.requested(args)).isTrue();
            assertThat(DatabaseSchemaCommand.run(args, environment, stream, stream)).isEqualTo(2);
        }
        assertThat(DatabaseSchemaCommand.run(new String[]{"--schema=migrate"}, Map.of(), stream, stream)).isEqualTo(2);
        assertThat(output.toString()).doesNotContain("private-secret", "private-user", "unexpected");
        assertThat(DatabaseSchemaCommand.requested(new String[]{"--server.port=8080"})).isFalse();
    }
}
