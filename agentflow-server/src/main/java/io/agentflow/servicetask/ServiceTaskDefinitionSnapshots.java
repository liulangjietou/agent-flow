package io.agentflow.servicetask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.DefinitionModels;
import io.agentflow.form.FormSchema;
import org.flowable.engine.RepositoryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

/**
 * 摘要固定已发布内容的规范结构，避免版本启停重新保存 JSON 时误改运行身份。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ServiceTaskDefinitionSnapshots {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final RepositoryService engine;
    /** 引擎定义标识只用于解析实际租户版本，不能从客户端节点参数推断。 */
    public ServiceTaskDefinitionSnapshots(JdbcTemplate jdbc, ObjectMapper mapper, RepositoryService engine) {
        this.jdbc = jdbc;
        this.json = new JsonUtil(mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS));
        this.engine = engine;
    }

    /** 激活时核对真实部署租户后读取固定发布正文。 */
    public Snapshot forEngine(String tenant, String runtimeDefinitionId) {
        var definition = engine.getProcessDefinition(runtimeDefinitionId);
        if (definition == null || !tenant.equals(definition.getTenantId())) throw invalid();
        return find(tenant, definition.getKey(), definition.getVersion());
    }

    /** 保留发布后的停用状态兼容；停用不改变已运行实例的原图与表单。 */
    public Snapshot find(String tenant, String key, long version) {
        return jdbc.query("""
                SELECT id,graph_json,form_schema_json FROM approval_definition
                WHERE tenant_id=? AND process_key=? AND version=? AND status='PUBLISHED'
                """, (row, index) -> {
                    String graph = row.getString("graph_json"); String schema = row.getString("form_schema_json");
                    var digest = ServiceTaskContract.sha256();
                    ServiceTaskContract.add(digest, "agentflow-service-definition-1", tenant, row.getString("id"), key, Long.toString(version));
                    addJson(digest, json.readStrict(graph, JsonNode.class));
                    ServiceTaskContract.add(digest, schema == null ? "ABSENT" : "PRESENT");
                    if (schema != null) addJson(digest, json.readStrict(schema, JsonNode.class));
                    return new Snapshot(key, version, json.read(graph, DefinitionModels.Graph.class), schema == null ? null : json.read(schema, FormSchema.class), HexFormat.of().formatHex(digest.digest()));
                }, tenant, key, version).stream().findFirst().orElseThrow(ServiceTaskDefinitionSnapshots::invalid);
    }

    // 对象键排序，数组保留原顺序；类型和元素数参与长度编码，拼接及类型转换不能碰撞。
    private static void addJson(MessageDigest digest, JsonNode value) {
        if (value.isObject()) {
            ServiceTaskContract.add(digest, "OBJECT", Integer.toString(value.size()));
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                ServiceTaskContract.add(digest, key);
                addJson(digest, value.get(key));
            }
        } else if (value.isArray()) {
            ServiceTaskContract.add(digest, "ARRAY", Integer.toString(value.size()));
            value.forEach(item -> addJson(digest, item));
        } else if (value.isTextual()) ServiceTaskContract.add(digest, "TEXT", value.textValue());
        else if (value.isNumber()) ServiceTaskContract.add(digest, "NUMBER", value.decimalValue().stripTrailingZeros().toString());
        else if (value.isBoolean()) ServiceTaskContract.add(digest, "BOOLEAN", Boolean.toString(value.booleanValue()));
        else if (value.isNull()) ServiceTaskContract.add(digest, "NULL");
        else throw invalid();
    }

    private static DomainException invalid() { return new DomainException("SERVICE_TASK_DEFINITION_MISMATCH", "Service task execution must belong to the exact published tenant definition"); }
    /** @author owlzhangfq@gmail.com */
    public record Snapshot(String key, long version, DefinitionModels.Graph graph, FormSchema schema, String digest) { }
}
