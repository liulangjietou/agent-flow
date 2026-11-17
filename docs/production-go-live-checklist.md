# 生产上线检查清单

本页把首次上线需要做的事按顺序串起来，每一步只写“做什么、怎样算完成”，具体做法链接到对应专题文档。它不替代专题文档，也不代表其中任何一步已经在目标环境完成；每一步都要在目标环境留下自己的记录。

适用范围：单主机 Docker Compose 部署（`compose.production.yml`），外部 PostgreSQL 17 和企业 OIDC。跨主机、Kubernetes 或其他编排平台需要另做部署设计。

## 1. 发布版本

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| 在 `main` 上推送 `vMAJOR.MINOR.PATCH` 标签 | `release-images` 作业通过，GHCR 中有 `-server` 和 `-web` 两个同版本镜像 | [生产容器](production-container-deployment.md#部署前准备) |
| 记录镜像摘要 | 环境文件中两个镜像都写成 `镜像@sha256:...`，不使用可变标签 | 同上 |
| 确认这一版本的 PR 流水线 | `verify` 与 `postgres-schema` 均通过 | [发布门禁](postgres-release-gate.md) |

## 2. 准备目标环境

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| PostgreSQL 17、迁移账号、运行账号、数据库 CA | 两个账号分离；连接串使用 `sslmode=verify-full` | [数据库生命周期](production-database-lifecycle.md) |
| 连接预算 | `副本数 × AGENTFLOW_DATASOURCE_POOL_SIZE` + 迁移与运维连接 < `max_connections` | [生产容器](production-container-deployment.md#配置文件与密钥) |
| 域名、TLS 证书链与私钥 | 证书与域名一致；有到期提醒或续期流程（本项目不提供自动续期） | 同上 |
| 密钥文件 | 每个密钥一个文件，所有者 UID 10001、权限 0400；环境文件中没有任何密码 | 同上 |
| 主机资源 | 每个后端副本预留 2 CPU、1536 MiB；日志磁盘按“容器数 × 单文件上限 × 保留个数”预留 | 同上 |
| 前置负载均衡（如有） | 已按文档配置 `set_real_ip_from`，限流和审计看到的是真实客户端地址 | [生产容器](production-container-deployment.md#验收与边界) |

## 3. 身份接入

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| 在 IdP 注册机密客户端 | 回调地址为 `<Origin>/api/v1/auth/oidc/callback/enterprise`，启用 PKCE 与 RS256 | [OIDC](enterprise-oidc.md) |
| 租户与角色映射 | 实际要用的角色都有映射，含财务与出纳；财务与出纳不是同一外部角色 | [企业映射模板](../deploy/production/enterprise.example.yml) |
| 内部 CA 签发的 IdP | 服务器 JVM 已挂载企业信任库，没有关闭证书校验 | [生产容器](production-container-deployment.md#配置文件与密钥) |
| 多副本或需要重启后保持登录 | `AGENTFLOW_JDBC_SESSIONS=true`（生产编排默认开启） | [共享会话](shared-enterprise-sessions.md) |

## 4. 首次安装

按 [生产容器 · 首次安装](production-container-deployment.md#首次安装) 依次执行 `config --quiet` → `migrate` → `migrate --schema=validate` → `nginx -t` → `up --wait`。

- `migrate` 和 `validate` 都退出 0，DBA 已为运行账号补齐新对象授权。
- 浏览器访问 HTTPS Origin，证书有效；`/actuator/health/readiness` 返回 200，其他 `/actuator/` 路径返回 404。
- 每类角色用一个真实账号登录、退出，确认看到的功能与角色一致。

## 5. 租户与业务初始化

空库没有流程、组织和审批人，健康检查通过不代表能办理业务。

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| 管理员完成初始化向导 | 组织、日历和本人通知选择已保存 | [租户初始化](tenant-initialization.md) |
| 组织与人员 | 人员绑定 IdP 的稳定 `sub`，审批人具备审批资格 | [本地组织](local-organization.md)、[组织同步](organization-sync.md) |
| 流程 | 至少一条真实流程完成发布、提交、审批的闭环 | [首次引导](first-workflow-guide.md) |
| 费用（如启用） | 费用制度与科目映射已发布正式版本 | [费用制度](expense-configuration.md)、[科目映射](account-mapping-configuration.md) |

## 6. 外部集成（按启用范围）

每个集成默认关闭。启用一个就完成一个的联调，不要一次全部打开。凭据按 [外部集成的凭据](production-container-deployment.md#外部集成的凭据) 用文件密钥注入。各集成的联调完成标准见 [任务台账](remaining-task-ledger.md) 的 E01–E14。

| 集成 | 参考 |
| --- | --- |
| 邮件、企业微信 | [通知投递](notification-delivery.md)、[企业微信](wecom-notifications.md) |
| 财务主数据、验票、预算、ERP、资金 | [财务网关](finance-gateway.md)、[付款回调](payment-callbacks.md) |
| Agent 模型 | [受控模型执行](agent-execution.md)、[报销 Agent 验收](enterprise-expense-agent-acceptance.md) |
| 附件与票据原件 | [附件](field-attachments.md) |
| Webhook、事件 | [Webhook](webhook-delivery.md)、[事件契约](event-contracts.md) |
| 电子签、服务任务 | [电子签](electronic-signatures.md)、[服务任务](service-tasks.md) |

## 7. 监控与告警

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| 叠加 `compose.monitoring.yml` | Prometheus 能采集每个副本，七条规则加载 | [监控](production-monitoring.md) |
| 接入企业 Alertmanager | 人为触发一条告警，收件人实际收到并收到恢复通知 | 同上 |
| Prometheus 自身 | 由企业外部监控检查存活 | 同上 |

## 8. 备份与恢复

| 做什么 | 完成标准 | 参考 |
| --- | --- | --- |
| 定时备份 | 由企业调度器按目标 RPO 执行，归档加密并有异地副本 | [生产备份恢复](production-backup-recovery.md) |
| 启用附件时 | 停写后数据库与附件目录配套备份 | [原件配套恢复](field-attachments.md#数据库与文件配套恢复) |
| 恢复演练 | 恢复到新库，原发布包 `validate` 通过，原在审单能继续审批；记录实际耗时作为 RTO | [生产备份恢复](production-backup-recovery.md#隔离恢复) |

## 9. 容量

用目标环境的数据量、并发和附件规模重测，并确定 SLO。现有[容量基线](capacity-baseline.md)是 2026-09-23 的旧版本、单机 Docker Desktop 结果，不能作为生产容量承诺。

## 10. 升级与回退

- 升级必须停机：停止全部应用节点和 worker → 备份 → `migrate` → `validate` → 启动 → 核对旧单据继续办理。见 [数据库生命周期](production-database-lifecycle.md#停机升级)。
- 回退等于“恢复升级前的备份 + 使用旧镜像”，不能把旧镜像直接连到新结构上。
- 保留上一版本的镜像摘要、环境文件和 `enterprise.yml`。

## 仓库侧（由仓库管理员在 GitHub 设置中完成）

这些不在代码里，需要在仓库设置中开启：

- `main` 分支保护：要求 `verify` 与 `postgres-schema` 通过后才能合入。
- 开启 Dependency graph 和 Dependabot alerts，然后把仓库变量 `DEPENDENCY_REVIEW_ENABLED` 设为 `true`，CI 中的依赖审查作业才会运行。
- 处理积压的 Dependabot PR。Spring Boot 4、Flowable 8、TypeScript 7、vue-router 5 都是大版本升级，需要单独评估和回归，不要在首次上线前合入。
