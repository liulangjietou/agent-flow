package io.agentflow.servicetask;

import java.util.List;

/**
 * 设计选项只暴露契约与可用状态，不携带部署目标、认证信息或执行输入。
 * @author owlzhangfq@gmail.com
 */
public final class ServiceTaskCatalogViews {
    private ServiceTaskCatalogViews() { }

    /** @author owlzhangfq@gmail.com */
    public record Option(String key, String version, String name, String contractDigest,
                         List<ServiceTaskContract.Parameter> parameters, boolean enabled) {
        public Option { parameters = List.copyOf(parameters); }
    }

    /** @author owlzhangfq@gmail.com */
    public record Directory(List<Option> items, String nextAfterKey) {
        public Directory { items = List.copyOf(items); }
    }

    /** @author owlzhangfq@gmail.com */
    public record Versions(List<Option> items, String nextBeforeVersion) {
        public Versions { items = List.copyOf(items); }
    }
}
