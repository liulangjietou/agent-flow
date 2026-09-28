package io.agentflow.finance;

import io.agentflow.common.CurrentActor;
import io.agentflow.common.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * 费用编辑器读取当前员工的授权目录，不允许查询任意员工或租户的财务主数据。
 * @author owlzhangfq@gmail.com
 */
@RestController
public class FinanceCatalogController {
    private final CurrentActor actors;
    private final FinanceMasterDataPort masterData;

    /** 身份来自平台认证，目录取自真实配置端口。 */
    public FinanceCatalogController(CurrentActor actors, FinanceMasterDataPort masterData) { this.actors = actors; this.masterData = masterData; }

    /** 目录时效由端口核对，浏览器和代理不得缓存个人授权目录。 */
    @GetMapping("/api/v1/finance/catalog")
    public ResponseEntity<FinanceCatalog> catalog(@RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new DomainException("INVALID_FINANCE_QUERY", "Finance catalog does not accept query parameters");
        var actor = actors.actor();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(masterData.catalog(actor.tenantId(), actor.userId()).requireValue());
    }
}
