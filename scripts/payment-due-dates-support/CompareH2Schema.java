import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.h2.util.ScriptReader;

/**
 * 逐条比较完整 SQL，忽略导出对象顺序而保留语句内部结构和重复数量。
 * @author owlzhangfq@gmail.com
 */
class CompareH2Schema {
    /** H2 自身的语句解析器识别引号和注释，不能把行集合当作结构等价。 */
    public static void main(String[] args) throws Exception {
        var before = statements(Path.of(args[0]));
        var after = statements(Path.of(args[1]));
        if (!before.equals(after)) throw new IllegalStateException("Restored schema statements differ");
        System.out.println("{\"equal\":true,\"statements\":" + before.size() + "}");
    }
    private static List<String> statements(Path path) throws Exception {
        var result = new ArrayList<String>();
        try (var reader = new ScriptReader(Files.newBufferedReader(path))) {
            reader.setSkipRemarks(true);
            for (String statement; (statement = reader.readStatement()) != null;) {
                if (!statement.isBlank()) result.add(statement.strip());
            }
        }
        Collections.sort(result);
        return result;
    }
}
