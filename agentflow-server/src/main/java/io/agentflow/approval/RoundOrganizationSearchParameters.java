package io.agentflow.approval;

import java.util.List;
import java.util.Locale;

/**
 * 待办与运营共用轮次组织名称谓词，固定使用已由调用方授权关联的 r 别名。
 *
 * @author owlzhangfq@gmail.com
 */
public final class RoundOrganizationSearchParameters {
    private RoundOrganizationSearchParameters() {}

    /** 追加名称包含匹配；输入已在入口校验，值全部参数化，通配符按字面量处理。 */
    public static void append(List<Object> parameters, String organization) {
        if (organization.isEmpty()) return;
        String pattern =
                "%"
                        + organization
                                .toLowerCase(Locale.ROOT)
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%";

        parameters.addAll(List.of(pattern, pattern, pattern));
    }
}
