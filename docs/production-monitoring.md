# 生产指标采集与告警规则

该能力为运维人员提供 JVM、HTTP、进程、Hikari 连接池和期望副本数指标，以及七条基础告警规则。默认关闭，不读取审批表单、身份声明、附件或 Agent 内容。通知收件人、外部 Alertmanager、生产 SLO 和监控高可用仍需目标环境配置与验收。

## 调用链和职责

Prometheus 定时读取 `/actuator/prometheus`；`BearerAuthFilter` 将这个固定入口交给 `MetricsScrapeAuthentication` 校验专用文件凭证，然后由 Spring Boot Actuator/Micrometer 返回运行指标。凭证不转换为 `CurrentActor`，不能访问任何审批或管理 API；业务管理员登录和 OIDC 会话也不能代替采集凭证。逻辑属于接入认证和基础设施，不进入审批领域模型或事务。

未启用时固定入口返回 404。启用但凭证缺失、错误或出现多个 Authorization 头时返回 401。正确凭证只允许 GET/HEAD；OIDC 模式下不安全方法还可能先被 CSRF 拒绝。响应使用 `Cache-Control: no-store`。普通健康检查继续保留最小匿名响应。

令牌从 `AGENTFLOW_METRICS_TOKEN_FILE` 在启动时读取，仅在内存中保存摘要并恒定时间比较摘要。文件内容为 43–256 个 URL 安全字符（字母、数字、`_`、`-`），允许一个末尾换行；建议用密码学随机源生成至少 32 字节熵的令牌。字符长度限制不代替随机性要求。缺失、过长、空文件或非法字符使启动失败，异常不包含文件路径、内容和底层原因。

## 启用容器采集

在[生产部署环境文件](../deploy/production/production.env.example)中配置专用令牌文件、经验证的 Prometheus 镜像和本机控制台端口。令牌不要复用登录、数据库或 OIDC 密钥。服务器 UID 10001 与 Prometheus 运行用户都需有文件读取权限；通过宿主机受控目录、组或 ACL 授权，Compose 文件 secrets 不会自动修复宿主机权限。

在代码根目录执行：

```bash
docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.monitoring.yml config --quiet

docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.monitoring.yml up -d --wait
```

叠加配置只给服务器和 Prometheus 挂载采集令牌；迁移和 Web 容器不获得它。服务器没有宿主机端口，公网 Nginx 仍拒绝该指标路径。Prometheus 控制台只绑定宿主机 `127.0.0.1:9090`，适合本机或受控 SSH 隧道访问；不要直接向公网发布未鉴权的控制台。

采集配置每五秒通过 Docker DNS 查询 `server` 的 A 记录，按每个 IP 的 8080 端口分别采集；`instance` 标签为该 IP 与端口。容器退出后 DNS 可能移除目标，因此另外比较可采集实例数和 `agentflow_runtime_expected_instances`，避免剩余实例正常掩盖副本缺口。期望值与 Compose 的 `AGENTFLOW_SERVER_REPLICAS` 共用一项配置；独立部署时所有节点须配置相同的 `AGENTFLOW_EXPECTED_INSTANCES`。

容器内部采集为 HTTP；跨主机或不可信网络必须另行配置 TLS/mTLS 和网络隔离。其他编排平台需配置对应服务发现，不能把负载均衡地址当作全部实例的观测结果。实例 IP 改变会开始新的时间序列；应结合发布记录解释历史曲线。

指标数据使用独立 `monitoring-data` 卷，保留上限为 15 天或 2 GB（先达到者）。停用时保留该卷。令牌轮换后重新创建服务器和采集器，使两侧读取同一新文件；只重启可能仍保留旧文件绑定。

## 告警语义

| 规则 | 初始阈值 | 排查方向 |
|---|---|---|
| AgentFlowReplicaShortfall | 可采集实例数少于部署期望，持续 2 分钟 | 节点退出、发现缺口；不会把仅剩一台正常误报为全部正常 |
| AgentFlowInstanceDown | 同一目标采集失败持续 2 分钟 | 实例、采集鉴权和网络；不直接认定业务停机 |
| AgentFlowScrapeTargetMissing | 整个 job 没有目标持续 5 分钟 | 配置或服务发现；无法检测采集器自身停机 |
| AgentFlowHttpServerErrors | 5 分钟至少 20 个 API 请求，5xx 比例超过 5%，持续 5 分钟 | 应用异常、数据库故障 |
| AgentFlowSlowRequests | 同样的请求量门槛，超过 2 秒的比例大于 5%，持续 10 分钟 | 数据库、长事务、并发和外部依赖 |
| AgentFlowDatabaseConnectionTimeouts | 最近 5 分钟连接获取超时计数增长，持续 1 分钟 | 池等待、数据库连接和长事务 |
| AgentFlowHeapPressure | 堆使用率超过 90%，持续 10 分钟 | GC、负载和内存增长 |

这些是可调整的初始运维阈值，不代表平台已通过生产 SLO 验收。HTTP 标签采用路由模板；不新增租户、用户、申请 ID、请求参数或表单值标签。鉴权前失败的请求可能归为 `UNKNOWN`，API 错误率规则只统计 `/api/` 路由，不作为攻击监控或完整请求审计。多实例按 `instance` 分开告警；计数器重启由 Prometheus 的 rate/increase 处理。

Prometheus 会展示 pending/firing 状态。需要邮件、IM 等通知时，在 `deploy/monitoring/prometheus.yml` 配置企业 Alertmanager，并独立验收路由、静默、恢复通知和收件人。当前配置不会向外部地址发送告警，不包含空接收器伪装成通知已交付。Prometheus 自身应由企业外部监控检查。

## 验证和依据

`MetricsScrapeIntegrationTest` 使用真实 HTTP 验证凭证隔离、路由标签、数据库/JVM 指标；`MetricsScrapeAuthenticationTest` 覆盖文件和请求头边界。`SystemChecksIntegrationTest` 验证默认关闭。`deploy/monitoring/alerts.test.yml` 使用确定时间序列验证七条规则的触发、等待、恢复、正常值、低流量及 DNS 移除节点；CI 用固定摘要镜像中的 promtool 执行配置校验和规则测试。

指标和端点配置依据 [Spring Boot 3.5 指标文档](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)和[端点文档](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)；规则验证依据 [Prometheus 规则测试文档](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)。实际依赖由本项目 Spring Boot 3.5.6 管理，验收以锁定构建为准。
