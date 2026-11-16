package io.agentflow.support;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.h2.engine.Database;
import org.h2.engine.Engine;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 每个顶层测试类结束后释放已无连接的 H2 内存库。
 * <p>
 * 测试普遍以 DB_CLOSE_DELAY=-1 保证类内多次取连接、关闭并重建上下文时数据仍在，但这样的库在同一 JVM 内永不释放；
 * 全量测试累计数百个库会耗尽 CI 默认堆，先拖慢再触发 OutOfMemoryError。类结束后仍有会话的库属于缓存中的
 * Spring 上下文（连接池常驻连接），保持不动；没有会话的库已无人可用，单独运行该类时同样拿不到它。
 * 嵌套类结束时外层类仍在运行，因此只在顶层类结束时清理。
 * </p>
 * @author owlzhangfq@gmail.com
 */
public final class UnusedInMemoryDatabases implements AfterAllCallback {
    private static final Field DATABASES = field(Engine.class, "DATABASES");
    private static final Field HELD_DATABASE = field(holderType(), "database");

    @Override
    public void afterAll(ExtensionContext context) {
        if (context.getParent().flatMap(ExtensionContext::getParent).isPresent()) return;
        for (Database database : openDatabases()) {
            if (!database.isPersistent() && !database.isClosing() && database.getSessions(false).length == 0) {
                database.shutdownImmediately();
            }
        }
    }

    /** 引擎表由 H2 自身加锁维护；只在锁内复制快照，关闭动作在锁外执行以免与其注销逻辑互相等待。 */
    private static List<Database> openDatabases() {
        try {
            Map<?, ?> holders = (Map<?, ?>) DATABASES.get(null);
            List<Database> databases = new ArrayList<>();
            synchronized (holders) {
                for (Object holder : holders.values()) {
                    Database database = (Database) HELD_DATABASE.get(holder);
                    if (database != null) databases.add(database);
                }
            }
            return databases;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("H2 database registry is not readable", failure);
        }
    }

    private static Class<?> holderType() {
        for (Class<?> nested : Engine.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("DatabaseHolder")) return nested;
        }
        throw new IllegalStateException("H2 Engine.DatabaseHolder is missing; review UnusedInMemoryDatabases after upgrading H2");
    }

    /** H2 升级改变内部结构时立即失败，避免清理悄然失效后重新出现堆耗尽。 */
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException("H2 field " + owner.getName() + "." + name + " is missing; review UnusedInMemoryDatabases after upgrading H2", failure);
        }
    }
}
