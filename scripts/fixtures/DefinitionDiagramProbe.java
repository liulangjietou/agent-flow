import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;

/**
 * 停服后只读提取实际部署资源和原定义文本；输出为编码后的有界验收记录。
 * @author owlzhangfq@gmail.com
 */
class DefinitionDiagramProbe {
    public static void main(String[] args) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0]
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT id,process_key,version,status,graph_json FROM approval_definition
                    WHERE tenant_id='demo' AND process_key LIKE 'diagram-%' ORDER BY process_key,version,id
                    """)) {
                while (rows.next()) result.add(String.join("\t", "D", rows.getString(1), rows.getString(2),
                        String.valueOf(rows.getLong(3)), rows.getString(4), encoded(rows.getString(5).getBytes(StandardCharsets.UTF_8))));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT p.ID_,p.KEY_,p.VERSION_,b.BYTES_ FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b
                      ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_
                    WHERE p.TENANT_ID_='demo' AND p.KEY_ LIKE 'diagram-%' ORDER BY p.KEY_,p.VERSION_
                    """)) {
                while (rows.next()) result.add(String.join("\t", "P", rows.getString(1), rows.getString(2),
                        String.valueOf(rows.getLong(3)), encoded(rows.getBytes(4))));
            }
        }
        Files.write(Path.of(args[1]), result, StandardCharsets.UTF_8);
    }

    private static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
}
