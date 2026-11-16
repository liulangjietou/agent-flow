package io.agentflow.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 只释放已无连接的内存库；仍被连接池或嵌套类外层使用的库保持原数据。
 * @author owlzhangfq@gmail.com
 */
class UnusedInMemoryDatabasesTest {
    private final UnusedInMemoryDatabases cleanup = new UnusedInMemoryDatabases();

    @Test void topLevelClassEndReleasesAbandonedDatabasesButKeepsThoseStillConnected() throws SQLException {
        String abandoned = seeded(), held = seeded();
        try (Connection pooled = DriverManager.getConnection(held, "sa", "")) {
            cleanup.afterAll(topLevelClass());
            assertThatThrownBy(() -> DriverManager.getConnection(existing(abandoned), "sa", "").close()).isInstanceOf(SQLException.class);
            assertThat(rows(pooled)).isEqualTo(1);
        }
    }

    @Test void nestedClassEndLeavesTheEnclosingClassDatabasesUntouched() throws SQLException {
        String outer = seeded();
        cleanup.afterAll(nestedClass());
        try (Connection reopened = DriverManager.getConnection(existing(outer), "sa", "")) {
            assertThat(rows(reopened)).isEqualTo(1);
        }
        cleanup.afterAll(topLevelClass());
    }

    /** 建库写入一行后关闭全部连接，模拟 DriverManagerDataSource 或已关闭上下文留下的库。 */
    private static String seeded() throws SQLException {
        String url = "jdbc:h2:mem:cleanup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE marker(id INT)");
            connection.createStatement().execute("INSERT INTO marker VALUES (1)");
        }
        return url;
    }

    /** IFEXISTS 阻止 H2 用同名空库掩盖已被释放的事实。 */
    private static String existing(String url) { return url + ";IFEXISTS=TRUE"; }

    private static int rows(Connection connection) throws SQLException {
        var result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM marker");
        result.next();
        return result.getInt(1);
    }

    private static ExtensionContext topLevelClass() {
        ExtensionContext engine = mock(ExtensionContext.class), type = mock(ExtensionContext.class);
        when(engine.getParent()).thenReturn(Optional.empty());
        when(type.getParent()).thenReturn(Optional.of(engine));
        return type;
    }

    private static ExtensionContext nestedClass() {
        ExtensionContext outer = topLevelClass(), nested = mock(ExtensionContext.class);
        when(nested.getParent()).thenReturn(Optional.of(outer));
        return nested;
    }
}
