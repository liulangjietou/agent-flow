package io.agentflow.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

/**
 * 仅读取明确数据库环境变量的一次性 PostgreSQL 部署命令，不启动应用上下文。
 * @author owlzhangfq@gmail.com
 */
public final class DatabaseSchemaCommand {
    private static final String PREFIX = "--schema";
    private static final String MIGRATE = "--schema=migrate";
    private static final String VALIDATE = "--schema=validate";
    private static final int POOL_SIZE = 4;
    private static final int CONNECTION_TIMEOUT_MILLIS = 10_000;
    private static final String SOCKET_TIMEOUT_SECONDS = "60";
    // PostgreSQL 会话级锁覆盖 Flyway 与 Flowable，防止两个部署命令交错执行。
    static final long MIGRATION_LOCK = 0x4147454e54464c4fL;

    private DatabaseSchemaCommand() { }

    /** 包括拼错的 schema 参数，避免误入普通服务器启动。 */
    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(argument -> argument.startsWith(PREFIX));
    }

    /** 返回 0 表示完成，2 表示输入错误，1 表示数据库检查或执行失败。 */
    public static int run(String[] args, Map<String, String> environment, PrintStream output, PrintStream error) {
        if (args.length != 1 || !(MIGRATE.equals(args[0]) || VALIDATE.equals(args[0]))) {
            error.println("Usage: java -jar agentflow-server.jar --schema=migrate|validate");
            return 2;
        }
        String url = environment.get("AGENTFLOW_DATASOURCE_URL");
        String username = environment.get("AGENTFLOW_DATASOURCE_USERNAME");
        String password = environment.get("AGENTFLOW_DATASOURCE_PASSWORD");
        if (StringUtils.isAnyBlank(url, username, password) || !url.startsWith("jdbc:postgresql://")) {
            error.println("Schema command requires explicit PostgreSQL URL, username and password environment variables");
            return 2;
        }
        // 数据库异常可能包含地址或凭证；命令输出只提供阶段与稳定错误码。
        var logging = (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        var root = logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var previous = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.OFF);
        String phase = "connect";
        try (var dataSource = new HikariDataSource(pool(url, username, password, VALIDATE.equals(args[0])));
             Connection lock = dataSource.getConnection()) {
            phase = "lock";
            acquire(lock);
            phase = MIGRATE.equals(args[0]) ? "migrate" : "validate";
            if (MIGRATE.equals(args[0])) {
                DatabaseSchemaLifecycle.migrate(dataSource);
            } else {
                DatabaseSchemaLifecycle.validate(dataSource);
            }
            output.println("Schema " + phase + " completed; business migrations and Flowable schema verified");
            return 0;
        } catch (Exception exception) {
            error.println("Schema command failed, errorCode=DATABASE_SCHEMA_FAILED, phase=" + phase
                    + ", errorType=" + exception.getClass().getSimpleName());
            return 1;
        } finally {
            root.setLevel(previous);
        }
    }

    private static HikariConfig pool(String url, String username, String password, boolean readOnly) {
        var pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(POOL_SIZE);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        pool.setInitializationFailTimeout(CONNECTION_TIMEOUT_MILLIS);
        pool.setReadOnly(readOnly);
        pool.addDataSourceProperty("readOnlyMode", "always");
        pool.addDataSourceProperty("connectTimeout", Integer.toString(CONNECTION_TIMEOUT_MILLIS / 1000));
        pool.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        pool.addDataSourceProperty("ApplicationName", "agentflow-schema");
        return pool;
    }

    private static void acquire(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, MIGRATION_LOCK);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Another schema command holds the migration lock");
                }
            }
        }
    }
}
