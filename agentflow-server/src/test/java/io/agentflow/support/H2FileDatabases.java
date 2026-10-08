package io.agentflow.support;

import java.nio.file.Path;

/**
 * 为文件库恢复测试提供显式 H2 配置，避免依赖生产环境的数据库默认值。
 * @author owlzhangfq@gmail.com
 */
public final class H2FileDatabases {
    private static final String DURABILITY_OPTIONS = ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE";

    private H2FileDatabases() { }

    /** 使用隔离的绝对路径并同步刷盘，保留已提交数据的强制退出恢复验证。 */
    public static String fileUrl(Path file) {
        return "jdbc:h2:file:" + file.toAbsolutePath() + DURABILITY_OPTIONS;
    }
}
