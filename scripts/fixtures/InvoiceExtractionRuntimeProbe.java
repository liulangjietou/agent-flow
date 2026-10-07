import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.JsonUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 停服后只读核对抽取运行与转换记录，不修改时钟、租约或业务数据。
 * @author owlzhangfq@gmail.com
 */
class InvoiceExtractionRuntimeProbe {
    /** 参数为本次 H2 文件前缀和新输出文件；调用方负责确认应用已停止。 */
    public static void main(String[] args) throws Exception {
        var json = new JsonUtil(new ObjectMapper());
        var result = new LinkedHashMap<String, Object>();
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + args[0]
                + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE", "sa", "")) {
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM agent_invoice_extraction_run ORDER BY created_at,id")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    var value = new LinkedHashMap<String, Object>();
                    value.put("status", rows.getString("status")); value.put("version", rows.getLong("version"));
                    value.put("state", json.read(rows.getString("state_json"), Object.class));
                    value.put("context", json.read(rows.getString("context_json"), Object.class));
                    value.put("leaseUntil", rows.getString("lease_until"));
                    var transitions = new ArrayList<Map<String, Object>>();
                    try (var history = connection.prepareStatement("SELECT run_version,status FROM agent_invoice_extraction_transition WHERE run_id=? ORDER BY run_version")) {
                        history.setString(1, id);
                        try (var records = history.executeQuery()) {
                            while (records.next()) transitions.add(Map.of("version", records.getLong(1), "status", records.getString(2)));
                        }
                    }
                    value.put("transitions", transitions); result.put(id, value);
                }
            }
        }
        Files.writeString(Path.of(args[1]), json.write(result));
    }
}
