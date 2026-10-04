package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import org.springframework.util.MultiValueMap;

import java.util.Set;
import java.util.UUID;

/**
 * 运行记录入口只接收轮次和有界翻页位置，租户与申请授权不取自查询参数。
 * @author owlzhangfq@gmail.com
 */
public record ServiceTaskRuntimeQuery(int roundNo, UUID afterId, int limit) {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> PARAMETERS = Set.of("afterId", "limit");

    /** 在入口一次校验正整数、完整 UUID 和唯一参数，游标所属轮次由查询服务核对。 */
    public static ServiceTaskRuntimeQuery parse(String roundNo, MultiValueMap<String, String> query) {
        try {
            if (!PARAMETERS.containsAll(query.keySet()) || query.values().stream().anyMatch(values -> values.size() != 1)
                    || !roundNo.matches("[1-9][0-9]{0,9}")) throw invalid();
            int round = Integer.parseInt(roundNo);
            String rawLimit = query.getFirst("limit");
            if (rawLimit != null && !rawLimit.matches("[1-9][0-9]{0,2}")) throw invalid();
            int limit = rawLimit == null ? DEFAULT_LIMIT : Integer.parseInt(rawLimit);
            if (limit > MAX_LIMIT) throw invalid();
            String rawId = query.getFirst("afterId");
            UUID after = rawId == null ? null : UUID.fromString(rawId);
            if (after != null && !after.toString().equals(rawId)) throw invalid();
            return new ServiceTaskRuntimeQuery(round, after, limit);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 非本申请或本轮游标采用相同错误，不返回其他记录的信息。 */
    static DomainException invalid() {
        return new DomainException("INVALID_SERVICE_TASK_QUERY", "Invalid service task runtime query");
    }
}
