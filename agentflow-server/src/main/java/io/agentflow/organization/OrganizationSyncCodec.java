package io.agentflow.organization;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import static io.agentflow.organization.OrganizationSyncKey.Kind;

/**
 * 外部组织协议只接收明确的组织事实；缺失字段、额外授权字段和重复 JSON 键均拒绝。
 * @author owlzhangfq@gmail.com
 */
@Component
public final class OrganizationSyncCodec {
    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private final JsonUtil json;

    /** 严格文档读取沿用统一 JSON 入口。 */
    public OrganizationSyncCodec(JsonUtil json) { this.json = json; }

    /** 返回事实前核对可信请求的租户、来源及起始游标，不从响应选择本地租户。 */
    public OrganizationSyncDelta read(String body, String tenant, String sourceKey, long afterRevision) {
        if (body == null || body.length() > MAX_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        try {
            var root = json.readStrict(body, JsonNode.class);
            fields(root, "contractVersion", "tenantId", "sourceKey", "afterRevision", "revision", "units", "people", "appointments");
            if (number(root.get("contractVersion")) != CONTRACT_VERSION || !string(root.get("tenantId")).equals(tenant)
                    || !string(root.get("sourceKey")).equals(sourceKey) || number(root.get("afterRevision")) != afterRevision) throw invalid();
            return new OrganizationSyncDelta(sourceKey, afterRevision, number(root.get("revision")),
                    list(root.get("units"), this::unit), list(root.get("people"), this::person), list(root.get("appointments"), this::appointment));
        } catch (DomainException | IllegalArgumentException failure) { throw invalid(); }
    }

    private OrganizationSyncDelta.Unit unit(JsonNode node) {
        fields(node, "externalId", "kind", "name", "legalEntityId", "parentDepartmentId", "active", "headAppointmentId");
        var key = new OrganizationSyncKey(Kind.valueOf(string(node.get("kind"))), string(node.get("externalId")));
        return new OrganizationSyncDelta.Unit(key, string(node.get("name")), reference(node.get("legalEntityId"), Kind.LEGAL_ENTITY),
                reference(node.get("parentDepartmentId"), Kind.DEPARTMENT), bool(node.get("active")), reference(node.get("headAppointmentId"), Kind.APPOINTMENT));
    }

    private OrganizationSyncDelta.Person person(JsonNode node) {
        fields(node, "externalId", "subject", "displayName", "active", "approvalEligible");
        return new OrganizationSyncDelta.Person(new OrganizationSyncKey(Kind.PERSON, string(node.get("externalId"))),
                string(node.get("subject")), string(node.get("displayName")), bool(node.get("active")), bool(node.get("approvalEligible")));
    }

    private OrganizationSyncDelta.Appointment appointment(JsonNode node) {
        fields(node, "externalId", "personId", "departmentId", "positionId", "active", "supervisorAppointmentId");
        return new OrganizationSyncDelta.Appointment(new OrganizationSyncKey(Kind.APPOINTMENT, string(node.get("externalId"))),
                reference(node.get("personId"), Kind.PERSON), reference(node.get("departmentId"), Kind.DEPARTMENT),
                reference(node.get("positionId"), Kind.POSITION), bool(node.get("active")), reference(node.get("supervisorAppointmentId"), Kind.APPOINTMENT));
    }

    private static <T> List<T> list(JsonNode node, Function<JsonNode, T> reader) {
        if (!node.isArray() || node.size() > OrganizationSyncDelta.MAX_RECORDS) throw invalid();
        var result = new ArrayList<T>(node.size());
        node.forEach(value -> result.add(reader.apply(value))); return result;
    }
    private static OrganizationSyncKey reference(JsonNode value, Kind kind) { return value.isNull() ? null : new OrganizationSyncKey(kind, string(value)); }
    private static String string(JsonNode value) { if (!value.isTextual()) throw invalid(); return value.textValue(); }
    private static boolean bool(JsonNode value) { if (!value.isBoolean()) throw invalid(); return value.booleanValue(); }
    private static long number(JsonNode value) { if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(); return value.longValue(); }
    private static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject() || node.size() != allowed.length) throw invalid();
        var names = Set.of(allowed); node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw invalid(); });
    }
    private static DomainException invalid() { return new DomainException("INVALID_ORGANIZATION_SYNC_DATA", "Organization source response does not match the expected contract"); }
}
