import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/**
 * 离线全表摘要供安装包升级对照。
 * @author owlzhangfq@gmail.com
 */
class SnapshotH2 {
  /** 按旧列或完整列生成逐表行数及原始值摘要。 */
  public static void main(String[] args) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:file:" + args[0] + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
      var tables = new TreeMap<String,String>();
      try (var names = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"BASE TABLE"})) {
        while (names.next()) {
          String table = names.getString("TABLE_NAME"); var rows = new ArrayList<String>();
          try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
            while (result.next()) {
              var row = new StringBuilder();
              for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                if (args.length > 2 && table.equalsIgnoreCase("payment_authorization") && result.getMetaData().getColumnName(i).equalsIgnoreCase("due_date")) continue;
                int type = result.getMetaData().getColumnType(i);
                byte[] bytes = type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB
                    ? result.getBytes(i) : result.getString(i) == null ? null : result.getString(i).getBytes(StandardCharsets.UTF_8);
                row.append(bytes == null ? "-" : Base64.getEncoder().encodeToString(bytes)).append(';');
              }
              rows.add(row.toString());
            }
          }
          Collections.sort(rows); var hash = MessageDigest.getInstance("SHA-256");
          for (String row : rows) hash.update((row + "\n").getBytes(StandardCharsets.UTF_8));
          tables.put(table, rows.size() + "\t" + HexFormat.of().formatHex(hash.digest()));
        }
      }
      var lines = new ArrayList<String>(); tables.forEach((key,value) -> lines.add(key + "\t" + value));
      Files.write(Path.of(args[1]), lines);
    }
  }
}
