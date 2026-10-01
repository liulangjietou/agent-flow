package io.agentflow.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * 事件目录的公开投影不携带租户内部标识或任何来源凭据。
 * @author owlzhangfq@gmail.com
 */
public final class EventContractViews {
    private EventContractViews() { }

    /**
     * 设计选择固定发布版本，可用性修订仅供识别启停变化。
     * @author owlzhangfq@gmail.com
     */
    public record Option(String key, long version, String name, String sourceKey, String eventType, int envelopeVersion,
                         boolean enabled, long availabilityRevision) {
        /** 从同一次读取的正文和可用性构造选择摘要。 */
        public static Option from(EventContractRepository.Version version) {
            var value = version.contract(); var state = version.availability();
            return new Option(value.key(), value.version(), value.name(), value.sourceKey(), value.eventType(), value.envelopeVersion(), state.enabled(), state.revision());
        }
    }

    /**
     * 一条明确的发布或启停决定，历史原样保存。
     * @author owlzhangfq@gmail.com
     */
    public record Availability(long revision, boolean enabled, String changedBy, Instant changedAt, String reason) {
        /** 去掉存储用关联字段，历史接口的路径已经明确具体契约和版本。 */
        public static Availability from(EventContractAvailability value) {
            return new Availability(value.revision(), value.enabled(), value.changedBy(), value.changedAt(), value.reason());
        }
    }

    /**
     * 管理员核对发布正文和最新启停事实，两个版本号保持各自语义。
     * @author owlzhangfq@gmail.com
     */
    public record Detail(String key, long version, String name, String sourceKey, String eventType, int envelopeVersion,
                         String publishedBy, Instant publishedAt, String publicationReason, Availability availability) {
        /** 只投影该请求明确指定的契约版本。 */
        public static Detail from(EventContractRepository.Version version) {
            var value = version.contract();
            return new Detail(value.key(), value.version(), value.name(), value.sourceKey(), value.eventType(), value.envelopeVersion(),
                    value.publishedBy(), value.publishedAt(), value.publicationReason(), Availability.from(version.availability()));
        }
    }

    /**
     * 目录最后一页显式返回空游标。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Directory(List<Option> items, String nextAfterKey) { }

    /**
     * 发布版本不与启停修订共用分页位置。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Versions(List<Option> items, Long nextBeforeVersion) { }

    /**
     * 当前精确版本的可用性历史，包含首次发布。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record History(List<Availability> items, Long nextBeforeRevision) { }
}
