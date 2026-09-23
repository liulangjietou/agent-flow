import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 在备份副本上验证 H2 关闭重开不会改变任何 PUBLIC 表，原始备份不会被数据库打开。
 * @author owlzhangfq@gmail.com
 */
public final class CheckH2Restart {
    private static final int REOPEN_COUNT = 3;

    /** 参数为已停服的无密码演示库备份和必须不存在的验收目录；H2 驱动由 classpath 提供。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].endsWith(".mv.db") || !Files.isRegularFile(Path.of(args[0]))) {
            throw new IllegalArgumentException("Expected an offline .mv.db backup and a new verification directory");
        }
        Path directory = Files.createDirectory(Path.of(args[1]).toAbsolutePath());
        Files.copy(Path.of(args[0]), directory.resolve("restart.mv.db"));
        String url = "jdbc:h2:file:" + directory.resolve("restart") + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Map<String, String> expected = snapshot(url);
        Files.writeString(directory.resolve("before.txt"), expected.toString());
        for (int cycle = 1; cycle <= REOPEN_COUNT; cycle++) {
            Map<String, String> actual = snapshot(url);
            Files.writeString(directory.resolve("after-" + cycle + ".txt"), actual.toString());
            if (!expected.equals(actual)) {
                var changed = new ArrayList<String>();
                var names = new java.util.TreeSet<>(expected.keySet());
                names.addAll(actual.keySet());
                for (String name : names) {
                    if (!java.util.Objects.equals(expected.get(name), actual.get(name))) changed.add(name);
                }
                throw new IllegalStateException("H2 restart changed tables at cycle " + cycle + ": " + changed);
            }
            System.out.println("PASS restart=" + cycle + " tables=" + actual.size());
        }
    }

    private static Map<String, String> snapshot(String url) throws Exception {
        var tables = new TreeMap<String, String>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            var names = new ArrayList<String>();
            try (ResultSet metadata = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"BASE TABLE"})) {
                while (metadata.next()) names.add(metadata.getString("TABLE_NAME"));
            }
            if (names.isEmpty()) throw new IllegalStateException("No PUBLIC tables found in the backup");
            for (String name : names) {
                var rows = new ArrayList<String>();
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT * FROM \"" + name.replace("\"", "\"\"") + "\"")) {
                    int columns = result.getMetaData().getColumnCount();
                    while (result.next()) {
                        var row = new StringBuilder();
                        for (int column = 1; column <= columns; column++) {
                            int type = result.getMetaData().getColumnType(column);
                            byte[] value;
                            if (type == java.sql.Types.BINARY || type == java.sql.Types.VARBINARY
                                    || type == java.sql.Types.LONGVARBINARY || type == java.sql.Types.BLOB) {
                                value = result.getBytes(column);
                            } else {
                                String text = result.getString(column);
                                value = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                            }
                            row.append(value == null ? "-" : Base64.getEncoder().encodeToString(value)).append(';');
                        }
                        rows.add(row.toString());
                    }
                }
                // 排序消除物理行顺序变化；只输出数量与摘要，不写业务正文。
                rows.sort(String::compareTo);
                var digest = MessageDigest.getInstance("SHA-256");
                for (String row : rows) digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
                tables.put(name, rows.size() + ":" + HexFormat.of().formatHex(digest.digest()));
            }
        }
        return tables;
    }
}
