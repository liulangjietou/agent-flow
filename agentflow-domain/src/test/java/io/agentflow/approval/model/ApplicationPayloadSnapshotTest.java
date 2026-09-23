package io.agentflow.approval.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 嵌套明细不能通过调用方列表或聚合读取引用绕过版本控制。
 * @author owlzhangfq@gmail.com
 */
class ApplicationPayloadSnapshotTest {
    @Test
    void draftAndRevisionFreezeNestedRowsIncludingNullWithoutChangingRepresentation() {
        var row = new LinkedHashMap<String, Object>(); row.put("quantity", "0001.00"); row.put("note", null);
        var rows = new ArrayList<>(List.of(row));
        var application = Application.draft(UUID.randomUUID(), "demo", "SNAPSHOT", "flow", 1, "alice", "明细", Map.of("items", rows));
        row.put("quantity", "2"); rows.clear();
        List<?> saved = (List<?>) application.payload().get("items");
        assertThat(saved).hasSize(1);
        assertThat(((Map<?, ?>) saved.get(0)).get("quantity")).isEqualTo("0001.00");
        assertThat(((Map<?, ?>) saved.get(0)).containsKey("note")).isTrue();
        assertThat(((Map<?, ?>) saved.get(0)).get("note")).isNull();
        assertThatThrownBy(saved::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(((Map<?, ?>) saved.get(0))::clear).isInstanceOf(UnsupportedOperationException.class);
        var changed = new ArrayList<>(List.of(Map.of("quantity", "3")));
        application.revise(1, "补正明细", Map.of("items", changed)); changed.clear();
        assertThat((List<?>) application.payload().get("items")).hasSize(1);
        assertThat(application.version()).isEqualTo(2);
    }
}
