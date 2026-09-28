# 同版本多实例部署与故障接续

此能力让两个同版本应用实例共享登录、业务事务和投递租约，并通过统一 HTTPS 入口服务。它不提供数据库、入口或监控组件的高可用，也不承诺任意版本滚动升级。企业组织目录尚未接通，本阶段以已有发布流程验证执行链路。

后续开发分支已完成本地组织与[单机附件](field-attachments.md)。启用附件时，同主机副本必须通过 `compose.attachments.yml` 挂载同一物理目录并使用一致的容量配置；不能让每个实例分别写自己的容器层或独立目录。该附件方案不支持跨主机部署，也不将现有双实例会话验收作为跨主机文件可用性证明。配套备份前须停止所有副本的写入。

## 配置与启动

按照[企业容器部署](production-container-deployment.md)准备 PostgreSQL、证书、OIDC 映射与独立文件密钥，然后在受控的 `production.env` 设置：

```dotenv
AGENTFLOW_SERVER_REPLICAS=2
```

所有实例使用同一发布镜像、数据库、OIDC 客户端、租户/角色映射、Webhook 目的地配置和外部 HTTPS Origin。Compose 用这个变量同时设置副本数和监控期望值。不要单独用 `--scale` 覆盖副本数而保留旧期望值。

在代码根目录执行：

```bash
docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.monitoring.yml config --quiet
docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.monitoring.yml up -d --wait --wait-timeout 180
```

首次安装前须显式执行迁移和校验；扩容同版本时不额外迁移。升级时停止所有应用节点及 worker，再按[数据库维护说明](production-database-lifecycle.md)处理，不将共享会话解释为跨版本兼容证明。

副本增加会同时增加连接池、引擎和后台 worker 对数据库的访问。部署前按实例数、每实例连接池上限、维护任务和监控连接核对数据库总连接预算；本次双实例功能验收不提供生产并发容量承诺。

## 请求、会话和重试

调用链为浏览器 → HTTPS Nginx → Docker DNS 中的应用实例 → 共享 PostgreSQL。应用没有宿主机端口，不需要会话粘滞。

- 会话保存在 JDBC；另一实例可以读取已保存的登录上下文。会话策略、角色和租户映射在每个实例上继续复核。
- 幂等声明、业务写入与成功响应处于同一数据库事务。相同身份和幂等键的并发请求只产生一份成功业务结果，另一请求重放该响应；用户操作恢复仍须保留原键和原请求。
- Nginx 每次请求使用解析所得的独立地址列表，DNS 缓存有效期为 5 秒。读取遇到连接错误或超时时最多尝试两个地址；`proxy_next_upstream_timeout` 限制继续尝试的窗口，不是整个请求的硬截止时间。
- 不启用 `non_idempotent` 重试。已经发给后端的 POST 在响应丢失时不会由代理自动重放，交由现有业务幂等恢复处理。
- OIDC 路径显式关闭上游重试。授权回调虽使用 GET，却会消费一次性授权码；失败后通过重新登录恢复，不能像普通查询一样向第二实例重复发送。
- 代理仍保留无参数访问日志及现有转发头约束，指标端点不对公网开放。

节点恢复或容器地址变化由 DNS 自动发现，不要求重启代理。发现间隔内可能先尝试旧地址并等待连接超时；本次验证不能扩展为所有网络故障、全部进行中请求都无损。

## 投递租约与观测

Webhook worker 通过共享数据库的版本条件竞争租约。同一租约只有一个持有者；过期后另一个实例可接手，旧持有者的迟到确认不能覆盖新状态。结果不明的发送仍按既有规则重试，外部接收者必须按事件标识去重，不能将租约称为外部副作用恰好执行一次。

Prometheus 按 DNS 的每个地址分别采集，避免经负载均衡只看到某一个实例。部署期望值由每个节点暴露为 `agentflow_runtime_expected_instances`。节点从 DNS 消失后，`AgentFlowReplicaShortfall` 仍能发现实际可采集数量不足；整个 job 无目标由缺失规则覆盖。详情见[监控规则](production-monitoring.md)。

## 验证范围

- `SharedSessionIntegrationTest` 在 H2 和专用 PostgreSQL 上分别运行 10 项：包括两个 HTTP 服务的跨实例认证、并发创建/提交/批准、单节点停止后的接续，以及租约竞争和迟到确认。
- `scripts/verify_cluster_proxy.py` 创建两个隔离后端和真实生产 Nginx，检查流量分配、节点停止/恢复、POST 响应丢失和一次性 OIDC 回调不重放；夹具只写入 `/fyoung/tmp`，只删除本次记录的容器与网络。
- 两个真实 Java 容器、HTTPS 入口和 Prometheus 的验收记录见本次[证据文件](evidence/multi-instance-20260924.json)。身份源为本地签名夹具，流程为专用库内预先发布的测试数据，不代表企业组织初始化或流程发布已经可用。

### 故障修复依据

原代理没有保护一次性 OIDC GET，断开响应测试观察到同一回调到达两个后端；因此对 OIDC 路径关闭重试。开发中的共享动态 upstream 又出现停止节点后偶发 504：连接等待期间 DNS 缩为一个节点，Nginx 的共享节点列表可能改变正在执行的重试逻辑。固定版本源码中 `ngx_http_upstream_free_round_robin_peer` 的 single 分支会清零剩余尝试，取节点时也会检查配置版本。

最终配置沿用变量解析方式，让每个请求保留自己的候选列表；测试保持停止节点后连续 12 次读取成功的原断言，没有增加失败容忍。依据为 [Nginx 1.30.5 上游源码](https://github.com/nginx/nginx/blob/release-1.30.5/src/http/ngx_http_upstream_round_robin.c)与[代理重试文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_next_upstream)。

后续仍需真实企业 IdP/组织联调、目标环境 SLO 压测、跨主机及数据库故障、灾备、升级回退和外部告警渠道验收。
