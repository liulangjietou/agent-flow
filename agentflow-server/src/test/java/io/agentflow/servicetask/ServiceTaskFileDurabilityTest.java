package io.agentflow.servicetask;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.agentflow.support.H2FileDatabases.fileUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 使用显式 H2 文件库配置和独立进程，验证提交成功后立即退出仍能恢复原领取事实。
 * @author owlzhangfq@gmail.com
 */
class ServiceTaskFileDurabilityTest {
    @Test
    void committedClaimSurvivesImmediateProcessExitWithExplicitFileDatabaseSettings() throws Exception {
        Path directory = Files.createTempDirectory("agentflow-service-durability-");
        String url = fileUrl(directory.resolve("claim"));
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE durable_claim(id INT PRIMARY KEY, version INT)");
        }
        commitAndCrash(directory, url, "INSERT INTO durable_claim VALUES(1,2)");
        assertCommittedClaim(url);
    }

    @Test
    void committedUpdateSurvivesImmediateExitAfterReopeningFragmentedDatabase() throws Exception {
        Path directory = Files.createTempDirectory("agentflow-service-fragmented-");
        Path database = directory.resolve("claim");
        // 模拟旧配置产生的文件空洞；零保留期仅用于快速构造夹具，不属于应用配置。
        String legacyUrl = "jdbc:h2:file:" + database
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;MAX_COMPACT_TIME=0;RETENTION_TIME=0";
        try (var connection = DriverManager.getConnection(legacyUrl, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE durable_claim(id INT PRIMARY KEY, version INT)");
            statement.execute("INSERT INTO durable_claim VALUES(1,1)");
            statement.execute("CREATE TABLE payload(id INT PRIMARY KEY, body VARBINARY)");
            statement.execute("INSERT INTO payload SELECT X, SECURE_RAND(8192) FROM SYSTEM_RANGE(1,128)");
            statement.execute("UPDATE payload SET body=SECURE_RAND(8192)");
            statement.execute("CHECKPOINT");
            statement.execute("CHECKPOINT");
        }
        String url = fileUrl(database);
        commitAndCrash(directory, url, "UPDATE durable_claim SET version=2 WHERE id=1 AND version=1");
        assertCommittedClaim(url);
    }

    private static void commitAndCrash(Path directory, String url, String sql) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", System.getProperty("java.class.path"), Committer.class.getName(), url, sql)
                .redirectError(directory.resolve("child-error.log").toFile()).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> assertThat(process.inputReader().readLine()).isEqualTo("COMMITTED"));
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void assertCommittedClaim(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version FROM durable_claim WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).as("committed claim after immediate process exit").isEqualTo(2);
        }
    }

    /**
     * 子进程只写入专用临时数据库；父进程收到提交标记后立即结束该进程。
     * @author owlzhangfq@gmail.com
     */
    public static class Committer {
        /** 提交信号与连接关闭分开，避免正常关闭掩盖延迟刷盘窗口。 */
        public static void main(String[] args) throws Exception {
            try (var connection = DriverManager.getConnection(args[0], "sa", "")) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    if (statement.executeUpdate(args[1]) != 1) {
                        throw new IllegalStateException("Expected exactly one claim update");
                    }
                }
                connection.commit();
                System.out.println("COMMITTED");
                System.out.flush();
                Thread.sleep(30_000);
            }
        }
    }
}
