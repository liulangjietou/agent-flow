package io.agentflow.finance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 科目管理命令不接受租户、发布身份或目标地址，领域定义在入口构造时验证。
 * @author owlzhangfq@gmail.com
 */
public final class AccountMappingConfigurationInput {
    private AccountMappingConfigurationInput() { }

    /**
     * 零修订明确表示创建，其他值表示操作者核对过的原草稿。
     * @author owlzhangfq@gmail.com
     */
    public record Draft(@NotNull @Min(0) Long expectedRevision, @NotNull @Valid AccountMappingDefinition definition,
                        @NotBlank @Size(max = 2000) String comment) { }

    /**
     * 非费用科目允许未配置类别的零版；三版本均由管理员读取后确认。
     * @author owlzhangfq@gmail.com
     */
    public record Publish(@NotNull @Positive Long expectedDraftRevision, @NotNull @Min(0) Long expectedCategoryRevision,
                          @NotNull @Min(0) Long expectedActiveRevision, @NotBlank @Size(max = 2000) String comment) { }
}
