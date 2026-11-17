# 企业 HTTPS 容器部署

`compose.production.yml` 将现有服务器和 Web 镜像组成独立部署，连接企业提供的 PostgreSQL 与 OIDC。它启用 `prod`、JDBC 会话及 HTTPS，关闭演示账号；数据库迁移由显式维护命令执行。此文件不会启动或修改 `compose.demo.yml` 的数据库和数据卷。

本页描述部署工程能力。本地组织、财务、Agent 和通知能力的当前进度见[剩余清单](remaining-local-work.md)；真实企业 IdP、财务接口、模型和通知渠道仍需目标环境联调。健康探针与协议夹具验收不代表完整平台已经上线。

## 部署前准备

1. PostgreSQL 17 数据库、迁移账号和运行账号，以及数据库 CA 证书。迁移账号可修改结构；运行账号按业务、引擎、会话表的需要授予读写及序列权限，迁移历史表只需读取。新增版本迁移后应补齐新增对象授权。
2. 与公网或企业内网 DNS 名称一致的 HTTPS 完整证书链及私钥。入口默认绑定 `0.0.0.0:443`，本机验收应改为 `127.0.0.1` 和独立端口。
3. 可通过 HTTPS 访问的 OIDC 签发方、已注册的机密客户端、客户端密钥和明确的租户/角色映射。回调为 `<AGENTFLOW_WEB_ORIGIN>/api/v1/auth/oidc/callback/enterprise`，企业账号退出返回地址为该 Origin 的 `/`。
4. Docker Engine 与 Compose v2，以及已经过项目测试的同版本服务器/Web 镜像。先完成构建、记录镜像摘要，再部署；生产文件本身不隐式构建或使用 `latest` 默认值。

在代码根目录可使用现有 Dockerfile 构建发布镜像：

```bash
docker build -f deploy/Dockerfile.server -t registry.example/agentflow-server:release .
docker build -f deploy/Dockerfile.web -t registry.example/agentflow-web:release .
```

镜像地址和 `release` 必须替换成自己的发布版本；是否推送由企业镜像发布流程决定。本仓库不附带镜像仓库凭证。

也可以使用仓库自带的发布流水线：推送 `vMAJOR.MINOR.PATCH` 形式的标签（如 `v1.2.3`）后，CI 在全部检查通过后把工作区版本改写为 `1.2.3`，构建并推送 `ghcr.io/<owner>/<repo>-server:1.2.3` 与 `-web:1.2.3`，镜像带 OCI 版本、提交与来源标签。其他形式的标签会让发布作业失败，分支推送和 PR 只验证不发布。部署时用 `docker buildx imagetools inspect` 记录镜像摘要，并以 `镜像@sha256:...` 写入 `AGENTFLOW_SERVER_IMAGE` / `AGENTFLOW_WEB_IMAGE`。

## 配置文件与密钥

将 [环境模板](../deploy/production/production.env.example) 和 [企业映射模板](../deploy/production/enterprise.example.yml) 复制到代码目录之外的受控部署目录，例如 `/etc/agentflow/production.env` 与 `/etc/agentflow/enterprise.yml`，逐项替换占位值。环境文件仅存地址、账号、开关和文件路径，不存密码或私钥。

数据库默认示例使用 `sslmode=verify-full&sslrootcert=/run/secrets/database_ca`，同时校验证书链及 JDBC 地址中的主机名。`AGENTFLOW_DATABASE_CA_FILE` 指向部署宿主机上的数据库 CA 文件，它会只读挂载至运行和迁移容器。数据库地址必须能在容器网络中解析；不能把宿主机的 `localhost` 当作容器外部数据库地址。

每个后端副本默认限制 2 CPU、1536 MiB 内存（`AGENTFLOW_SERVER_CPUS`、`AGENTFLOW_SERVER_MEMORY`），JVM 最大堆取内存上限的 66%，堆耗尽时进程退出并由 `restart: unless-stopped` 拉起。宿主机需为全部副本预留对应内存。

数据库连接池为固定大小，默认每个后端副本 10 个连接（即[容量基线](capacity-baseline.md)实测配置），由 `AGENTFLOW_DATASOURCE_POOL_SIZE` 调整。规划时保证 `AGENTFLOW_SERVER_REPLICAS × AGENTFLOW_DATASOURCE_POOL_SIZE` 加上迁移和运维连接小于数据库 `max_connections`。若数据库前有防火墙或连接代理会回收空闲连接，把 `AGENTFLOW_DATASOURCE_MAX_LIFETIME_MS` 设得比该时限短。

密码和 OIDC 客户端密钥分别写入独立文件；文件内容是一行原始值，可有结尾换行，不加 shell 引号、不做变量替换。容器入口只读文件，值不会出现在 Compose 环境清单或 Java 启动参数中。容器进程环境仍包含运行所需的值，Docker 管理权限需要按企业制度控制。

服务器镜像以 UID/GID `10001` 运行。Linux 上应配置密钥文件所有者/组和权限，使该 UID 可读，其他无关账号不可读，例如所有者 `10001`、权限 `0400`；父目录保留受控访问。Compose 本地文件 secrets 使用绑定挂载，不依赖 `uid/gid/mode` 字段替你修改宿主机权限。TLS 私钥由 Nginx 主进程读取。缺失、空文件和同时配置文件/环境值的歧义会阻止启动。

启用[表单附件](field-attachments.md#启用持久目录)时，先准备 UID/GID `10001` 所有、权限 `0700` 的持久目录，并设置 `AGENTFLOW_ATTACHMENT_HOST_DIRECTORY`。在本页 Compose 命令中一并使用 `-f compose.attachments.yml`，包括后续重建；目录必须支持 POSIX 权限和硬链接。未配置时附件保持关闭，不会保存到容器临时层。已有附件的备份和恢复必须同时包含数据库和原文件。

租户与角色映射只授予明确声明所对应的平台身份，不会自动创建组织或审批人。请按 [OIDC 接入说明](enterprise-oidc.md)完成映射。平台共 8 个角色，[企业映射模板](../deploy/production/enterprise.example.yml)给出了全部角色的示例行；角色值按原文比对，拼错不会报错，只会让该身份拿不到权限。财务（FINANCE）和出纳（CASHIER）是职责分离的两端，不要映射给同一个外部角色。

### 外部集成的凭据

财务网关、付款回调、SMTP/企业微信、Webhook、事件来源、电子签、组织同步和白名单服务任务都按租户配置，写在 `enterprise.yml` 里，默认全部关闭。它们的凭据不写进 `enterprise.yml` 或环境文件，而是作为文件密钥挂载：`prod` 配置会把容器内 `/run/secrets/` 目录按“文件名 = 配置项名”导入（Spring 配置树，自动去掉结尾换行），`enterprise.yml` 用 `${文件名}` 引用即可。

在受控部署目录新建一个 override，例如 `/etc/agentflow/compose.integrations.yml`：

```yaml
services:
  server:
    secrets: [notification_smtp_password]
secrets:
  notification_smtp_password:
    file: /etc/agentflow/secrets/notification-smtp-password
```

再在 `enterprise.yml` 中写 `password: ${notification_smtp_password}`。部署命令在已有 `-f` 之后追加 `-f /etc/agentflow/compose.integrations.yml`，后续重建也要带上。文件权限要求同上（UID 10001 可读）。文件名只用小写字母、数字和下划线，不要与平台已有配置项重名；已挂载的 `database_password`、`oidc_client_secret` 等也会以同名配置项出现，不要在集成里复用它们。各集成的字段和启用顺序见对应专题文档。`AGENTFLOW_OIDC_BACKCHANNEL_LOGOUT` 默认 false，身份源正确注册通知地址并完成联调后再启用。

内部 CA 的身份源需要另外为服务器 JVM 配置可信证书库。可通过企业 Compose override 将信任库只读挂载，并设置 `JAVA_TOOL_OPTIONS` 的 `javax.net.ssl.trustStore` 等属性。不要关闭证书验证，也不要把验收用证书加入操作系统的全局信任。本阶段不提供企业 CA 自动分发。

## 首次安装

以下命令在代码根目录运行。`config --quiet` 只检查，不输出展开后的配置。

```bash
docker compose --env-file /etc/agentflow/production.env -f compose.production.yml config --quiet

docker compose --env-file /etc/agentflow/production.env -f compose.production.yml \
  --profile maintenance run --rm migrate

docker compose --env-file /etc/agentflow/production.env -f compose.production.yml \
  --profile maintenance run --rm migrate --schema=validate

docker compose --env-file /etc/agentflow/production.env -f compose.production.yml \
  run --rm --no-deps web nginx -t

docker compose --env-file /etc/agentflow/production.env -f compose.production.yml \
  up -d --wait --wait-timeout 180
```

迁移完成后，由 DBA 核对运行账号对新增对象的授权，再启动服务。迁移容器仅获得迁移密码及数据库 CA，不获得 OIDC 密钥；运行容器不获得迁移账号密码。普通 `up` 不运行带 `maintenance` profile 的迁移服务。

访问配置的 HTTPS Origin。检查证书和 `/actuator/health/readiness`，然后进行真实企业登录、授权范围及业务验证。后端和数据库没有本文件发布的宿主机端口；Nginx 的 8080 健康检查仅监听容器回环地址。

需要两个同版本应用副本时设置 `AGENTFLOW_SERVER_REPLICAS=2`，使用同一数据库和身份配置；设置会同时更新监控期望值。连接预算、代理重试、故障接续与限制见[多实例部署](multi-instance-deployment.md)。

## 升级与停止

先在隔离数据库副本演练新版本，按照[数据库升级说明](production-database-lifecycle.md)停写、备份并保留旧镜像和配置。在维护窗口停止所有应用节点及 worker；本文件只管理当前 Compose 项目，不能代替其他节点的停机。

```bash
docker compose --env-file /etc/agentflow/production.env -f compose.production.yml stop web server
```

更新环境文件中的发布镜像版本，再执行上面的 `migrate`、`validate`、`nginx -t` 和 `up --wait`。核对旧申请、待办继续办理、会话行为及健康状态后恢复入口。数据库升级失败时停止发布；不能把旧镜像直接连接新结构当作完整回滚。

普通停止保留容器和外部数据库。本文件没有数据库卷；不要将 `down -v` 用于共享环境清理。

容器日志使用 `json-file` 驱动并轮转：每个容器最多保留 `AGENTFLOW_LOG_MAX_FILES` 个（默认 5）、每个 `AGENTFLOW_LOG_MAX_SIZE`（默认 50m）的文件，宿主机按“容器数 × 两者乘积”预留磁盘。日志需要长期留存或集中检索时，用 override 改成企业日志平台的驱动，不要靠调大轮转上限保存审计证据；业务审计以平台审计记录为准。

证书或文件密钥轮换后，需要重新创建使用它们的容器，使绑定挂载重新读取新文件。例如 TLS 文件替换后，先运行证书配置检查，再 `up -d --force-recreate --wait web`。单纯重启可能仍引用原文件的 inode。密码轮换需与数据库/IdP 按维护计划协调。

## 验收与边界

- 客户端到 Nginx 使用 TLS；Nginx 到同一 Docker 网络内的后端使用 HTTP。跨主机后端、外部负载均衡或容器编排平台需单独部署设计，不据此宣称链路全部端到端加密。
- 会话使用 JDBC，容器重启后可恢复尚未过期且身份配置仍匹配的登录。共享会话不是多实例高可用验收。
- 代理覆盖或移除外来转发头，应用不依赖这些头生成回调；生产 Cookie 为 Secure、HttpOnly、SameSite=Lax。
- 代理访问日志只记录无查询参数的路径与状态，不记录 Cookie、Authorization、请求体或 Referer。OIDC 路径的 Nginx 原始错误日志关闭，保留无参数的状态日志，具体失败通过后端受控日志和身份服务诊断。
- 代理按客户端地址限流：`/api/v1/auth/oidc/` 每地址 10 次/秒（突发 50），其余接口 100 次/秒（突发 200），超限直接返回 429、不转发后端。企业出口 NAT 后多人共用一个地址时额度按整个出口计算；高峰仍触发 429 时调整 [nginx.conf](../deploy/production/nginx.conf) 中的 `limit_req_zone`。
- 限流假设 Nginx 直接面对客户端。如果前面还有企业负载均衡、WAF 或 CDN，`$remote_addr` 会变成它们的地址，所有用户共用一份额度，同时后端审计里的来源地址也只剩负载均衡。这种部署要在 `server` 块里用 `set_real_ip_from <负载均衡网段>;` 和 `real_ip_header X-Forwarded-For;`（或 `proxy_protocol`）恢复真实地址，并且只信任负载均衡的网段；配置后重跑 `nginx -t` 和限流验收。不要信任任意来源的 `X-Forwarded-For`，否则客户端可以伪造地址绕过限流。
- 前端静态资源（`/assets/`）按 gzip 压缩，接口响应不压缩：会话页面会同时返回 CSRF 令牌和用户可控内容，在 HTTPS 上压缩二者会给 BREACH 类长度推测留下条件。
- 代理统一补齐 HSTS、CSP、X-Frame-Options、X-Content-Type-Options 和 Referrer-Policy；后端已自行设置的同名头（例如企业退出跳转页的 nonce CSP）原样保留，不叠加第二条。HSTS 生效后浏览器在一年内只用 HTTPS 访问该域名。
- 只开放 readiness，其他 actuator 路径返回 404。可叠加[指标与告警规则](production-monitoring.md)；企业通知渠道、审计留存、证书自动续期、正式压测及灾备仍需完成。
- 本阶段使用本地测试 CA 和签名身份夹具验证协议与部署；企业 IdP、企业 CA、域名及防火墙必须在目标环境重新验收。

参考：[Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)、[Compose 必需变量](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)、[Nginx HTTPS 配置](https://nginx.org/en/docs/http/configuring_https_servers.html)。
