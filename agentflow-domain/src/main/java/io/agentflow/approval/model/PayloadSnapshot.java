package io.agentflow.approval.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 申请聚合与提交轮次共享的深度不可变 JSON 快照，保留原始空值和数字表示。
 * @author owlzhangfq@gmail.com
 */
public final class PayloadSnapshot {
    private PayloadSnapshot() { }

    /** 拷贝每一层对象和列表，外部引用不能在版本检查之外修改业务内容。 */
    public static Map<String, Object> copy(Map<String, Object> payload) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (payload != null) payload.forEach((key, value) -> snapshot.put(key, freeze(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            values.forEach((key, nested) -> copy.put(key, freeze(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) return values.stream().map(PayloadSnapshot::freeze).toList();
        return value;
    }
}
