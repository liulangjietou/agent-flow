package io.agentflow.servicetask;


import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import io.agentflow.definition.ServiceTaskPolicy;
import io.agentflow.mybatis.SqlRows;
import io.agentflow.servicetask.mapper.ServiceTaskCatalogMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 可信部署声明只追加安装，相同版本不能静默更换契约或目标；凭据不落入流程定义和目录。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class ServiceTaskCatalog {
    private final ServiceTaskCatalogMapper sqlMapper;
    private final JsonUtil json;
    private final ServiceTaskGatewayConfiguration configuration;

    /** 安装与读取共用精确租户边界。 */
    public ServiceTaskCatalog(
            ServiceTaskCatalogMapper sqlMapper,
            JsonUtil json,
            ServiceTaskGatewayConfiguration configuration) {
        this.sqlMapper = sqlMapper;
        this.json = json;
        this.configuration = configuration;
    }

    /** 启动时登记部署声明；整批原子提交，已有定义引用保持原字节和目标。 */
    @Transactional
    public void install() {
        var declared = configuration.declarations();
        if (declared.isEmpty()) return;
        SqlRows.single(sqlMapper.install());
        for (var value : declared) {
            var existing =
                    find(value.tenantId(), value.contract().key(), value.contract().version());
            if (existing.isPresent()) {
                if (!existing.get().contract().equals(value.contract())
                        || !existing.get().targetDigest().equals(value.targetDigest())) {
                    throw new DomainException(
                            "SERVICE_TASK_CONTRACT_REDEFINED",
                            "An installed service task version cannot change its contract or"
                                + " target");
                }
            } else
                sqlMapper.install2(
                        value.tenantId(),
                        value.contract().key(),
                        value.contract().version(),
                        value.contract().digest(),
                        value.targetDigest(),
                        json.write(value.contract()),
                        Timestamp.from(Instant.now()));
        }
    }

    /** 停用或移除配置不删除已安装的历史契约。 */
    public Optional<Installed> find(String tenant, String key, long version) {
        return SqlRows.map(
                        sqlMapper.find(tenant, key, version),
                        row -> {
                            var contract =
                                    json.read(
                                            row.getString("contract_json"),
                                            ServiceTaskContract.class);
                            if (!contract.key().equals(key)
                                    || contract.version() != version
                                    || !contract.digest()
                                            .equals(row.getString("contract_digest"))) {
                                throw new IllegalStateException(
                                        "Persisted service task contract identity is inconsistent");
                            }
                            return new Installed(contract, row.getString("target_digest"));
                        })
                .stream()
                .findFirst();
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

    /** 最新安装版本始终显示，停用和配置缺失只影响可用状态。 */
    public ServiceTaskCatalogViews.Directory list(String tenant, ServiceTaskCatalogQuery query) {
        var references =
                SqlRows.map(
                        sqlMapper.list(
                                tenant,
                                query.afterKey() == null ? "" : query.afterKey(),
                                query.limit() + 1),
                        row ->
                                new Reference(
                                        row.getString("operation_key"),
                                        row.getLong("latest_version")));
        var items =
                references.stream()
                        .limit(query.limit())
                        .map(reference -> option(tenant, reference.key(), reference.version()))
                        .toList();
        return new ServiceTaskCatalogViews.Directory(
                items,
                references.size() > query.limit() ? items.get(items.size() - 1).key() : null);
    }

    /** 历史版本按倒序分页，不把浏览器数字精度限制施加到已安装的原版本上。 */
    public ServiceTaskCatalogViews.Versions versions(
            String tenant, String key, ServiceTaskCatalogQuery query) {
        var arguments = new ArrayList<Object>(List.of(tenant, key));

        if (query.beforeVersion() != null) {

            arguments.add(query.beforeVersion());
        }
        arguments.add(query.limit() + 1);
        var versions =
                sqlMapper.versionsQuery((query.beforeVersion() != null), arguments.toArray());
        var items =
                versions.stream()
                        .limit(query.limit())
                        .map(version -> option(tenant, key, version))
                        .toList();
        return new ServiceTaskCatalogViews.Versions(
                items,
                versions.size() > query.limit() ? items.get(items.size() - 1).version() : null);
    }

    /** 精确读取不会退回到其他版本或租户，投影不包含目标摘要、地址和凭据。 */
    public ServiceTaskCatalogViews.Option option(String tenant, String key, long version) {
        var installed = find(tenant, key, version).orElseThrow(() -> new DomainException("NOT_FOUND", "Service task contract version not found"));
        var contract = installed.contract();
        return new ServiceTaskCatalogViews.Option(contract.key(), Long.toString(contract.version()), contract.name(), contract.digest(),
                contract.parameters(), available(tenant, installed));
    }

    private static DomainException unavailable() { return new DomainException("SERVICE_TASK_CONTRACT_UNAVAILABLE", "The exact service task contract version is unavailable"); }

    /**
     * @author owlzhangfq@gmail.com
     */
    public record Installed(ServiceTaskContract contract, String targetDigest) {}

    /**
     * @author owlzhangfq@gmail.com
     */
    private record Reference(String key, long version) {}
}
