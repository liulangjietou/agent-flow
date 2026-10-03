package io.agentflow.servicetask;

import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.ServiceTaskPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 可信部署声明只追加安装，相同版本不能静默更换契约或目标；凭据不落入流程定义和目录。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskCatalog {
    private final JdbcTemplate jdbc;
    private final JsonUtil json;
    private final ServiceTaskGatewayConfiguration configuration;

    /** 安装与读取共用精确租户边界。 */
    public ServiceTaskCatalog(JdbcTemplate jdbc, JsonUtil json, ServiceTaskGatewayConfiguration configuration) {
        this.jdbc = jdbc; this.json = json; this.configuration = configuration;
    }

    /** 启动时登记部署声明；整批原子提交，已有定义引用保持原字节和目标。 */
    @Transactional
    public void install() {
        var declared = configuration.declarations();
        if (declared.isEmpty()) return;
        jdbc.queryForObject("SELECT id FROM service_task_catalog_lock WHERE id=1 FOR UPDATE", Integer.class);
        for (var value : declared) {
            var existing = find(value.tenantId(), value.contract().key(), value.contract().version());
            if (existing.isPresent()) {
                if (!existing.get().contract().equals(value.contract()) || !existing.get().targetDigest().equals(value.targetDigest())) {
                    throw new DomainException("SERVICE_TASK_CONTRACT_REDEFINED", "An installed service task version cannot change its contract or target");
                }
            } else jdbc.update("""
                    INSERT INTO service_task_contract(tenant_id,operation_key,operation_version,contract_digest,target_digest,contract_json,installed_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, value.tenantId(), value.contract().key(), value.contract().version(), value.contract().digest(), value.targetDigest(),
                    json.write(value.contract()), Timestamp.from(Instant.now()));
        }
    }

    /** 停用或移除配置不删除已安装的历史契约。 */
    public Optional<Installed> find(String tenant, String key, long version) {
        return jdbc.query("SELECT contract_json,contract_digest,target_digest FROM service_task_contract WHERE tenant_id=? AND operation_key=? AND operation_version=?",
                (row, index) -> {
                    var contract = json.read(row.getString("contract_json"), ServiceTaskContract.class);
                    if (!contract.key().equals(key) || contract.version() != version || !contract.digest().equals(row.getString("contract_digest"))) {
                        throw new IllegalStateException("Persisted service task contract identity is inconsistent");
                    }
                    return new Installed(contract, row.getString("target_digest"));
                }, tenant, key, version).stream().findFirst();
    }

    /** 原流程激活读取其原契约；暂时停用只阻止外发，不重写历史节点。 */
    public Installed referenced(String tenant, ServiceTaskPolicy policy) {
        var value = find(tenant, policy.operationKey(), policy.operationVersion()).orElseThrow(ServiceTaskCatalog::unavailable);
        if (!value.contract().digest().equals(policy.contractDigest())) throw unavailable();
        return value;
    }

    /** 发布、发起和首次外发要求当前配置仍明确启用原目标。 */
    public boolean available(String tenant, Installed value) {
        return configuration.find(tenant, value.contract().key(), value.contract().version())
                .filter(current -> current.enabled() && current.contract().equals(value.contract()) && current.targetDigest().equals(value.targetDigest())).isPresent();
    }

    private static DomainException unavailable() { return new DomainException("SERVICE_TASK_CONTRACT_UNAVAILABLE", "The exact service task contract version is unavailable"); }
    /** @author owlzhangfq@gmail.com */
    public record Installed(ServiceTaskContract contract, String targetDigest) { }
}
