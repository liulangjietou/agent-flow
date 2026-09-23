# 演示安装与系统自检

当前提供本机演示环境：PostgreSQL、Java 17 服务与静态 Web 页面一起启动。已配置演示租户与账号；企业初始化向导、组织主数据与生产认证仍未实现。不要将此演示配置用于公网或正式审批。

## 一条命令启动

需要已启动的 Docker Engine/Desktop 与 Docker Compose v2+。首次构建需要网络获取官方镜像、Maven 和 npm 依赖，不要求宿主机安装 Java、Maven 或 Node.js。

在仓库根目录执行：

```bash
docker compose -f compose.demo.yml up --build -d --wait --wait-timeout 180
```

构建结束后等待数据库与后端健康检查通过，再启动 Web。打开 `http://127.0.0.1:8180`，租户 `demo`，账号 `admin` / `manager` / `finance` / `employee` / `alice` / `bob`，密码 `demo`。账号是演示身份，不表示已经完成企业管理员初始化。

默认仅发布 `127.0.0.1:8180`，数据库和后端没有宿主机端口映射。Web 同源代理保留原始 Host，CORS 只允许配置的前端来源。如果 8180 被占用，可以在同一命令前指定 `AGENTFLOW_DEMO_PORT=8181`，访问对应端口；之后的 Compose 操作也使用同一值。

```bash
AGENTFLOW_DEMO_PORT=8181 docker compose -f compose.demo.yml up --build -d --wait --wait-timeout 180
```

Compose 项目名固定为 `agentflow-demo`，便于从不同代码目录管理同一套演示实例。需并行独立实例时使用不同 `-p` 项目名和不同端口；每次管理实例时保持一致，避免误操作其他实例。

## 第一次审批

1. 用 `admin` 登录并打开“系统自检”，确认数据库、迁移、引擎、模板四项检查结果。
2. 点击“从模板开始”，复制请假、用印或合同模板，打开独立草稿。按演示需求调整表单、审批组和分支，校验后发布。
3. 使用 `alice` 发起申请；按模板配置使用 `manager` 或 `admin` 处理审批，再回到申请记录查看状态与真实轨迹。

内置费用报销示例也可用于通用申请演示。演示审批组不是组织关系解析，模板阈值需要按实际制度调整。自检与导航不会自动复制、发布或审批任何流程。

## 自检接口与状态

`GET /api/v1/system/checks` 仅允许 `ADMIN`，匿名返回 401，普通审批人及仅有流程管理权限的主体返回 403。响应禁止缓存，不返回连接地址、数据库账号、令牌、异常原文或其他租户的数量。

| 检查项 | 实际行为 |
|---|---|
| 数据库 | 当前数据源执行 `SELECT 1`，查询后释放连接 |
| 数据库迁移 | Flyway 只读验证校验和、当前版本和待执行项，不执行 migrate/repair/clean |
| Flowable | 查询当前租户的定义、运行实例、任务、历史；空租户有效 |
| 官方模板 | 返回启动时已加载、验证的模板与路由场景数量 |
| 身份认证 | 演示开启标记 `DEMO_AUTH_ONLY`；关闭后标记企业认证未配置 |
| 文件、通知、组织、模型 | 均为 `NOT_IMPLEMENTED`，不以配置字符串假装服务已接入 |

`UP` 表示本次检查成功；`DOWN` 表示检查执行失败；`UNKNOWN` 表示超时、繁忙或中断，尚不能判定依赖状态。`WARNING` 和 `NOT_IMPLEMENTED` 不计为通过。

每项外部查询最多等待 3 秒；整个请求最多执行三项查询。诊断使用一个后台线程和一个等待槽，超时后中断并清理排队任务；驱动忽略中断时后台任务可能继续，但不会无限创建线程。页面请求最多等待 12 秒，刷新、退出或切换账号会清空旧结果并取消请求。检查时间是本次快照完成时间，不表示持续监测。

公开探针仅放行 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness` 的 GET/HEAD，隐藏组件及细节。readiness 包含数据库，liveness 不因数据库故障重启进程。Web 入口仅代理 readiness，其他 actuator 路径返回 404。详细检查仍需认证。

服务尚未成功启动时，页面自检无法工作，应使用容器状态与日志定位启动问题。`prod` 配置仍关闭演示登录且尚无 OIDC 适配器，不能通过开放诊断接口绕过认证。

## 日常操作与保留数据

```bash
docker compose -f compose.demo.yml ps
docker compose -f compose.demo.yml logs --tail=100 server
docker compose -f compose.demo.yml stop
docker compose -f compose.demo.yml up -d --wait --wait-timeout 180
```

PostgreSQL 使用 `agentflow-demo_demo-postgres` 命名卷。`stop` 或不带 `-v` 的 `down` 保留卷；再次启动连接原库，Flyway 沿用迁移记录。不要执行 `down -v` 或删除数据卷，这会删除演示申请、定义和审批历史。更改演示数据库密码不会修改已初始化卷内的密码。

后端重启会使内存中的演示登录令牌失效，需要重新登录，业务数据保留。镜像升级先备份数据库，再使用 `up --build`；本安装方式不是自动迁移回滚方案。

镜像采用官方 `maven:3.9-eclipse-temurin-17`、`eclipse-temurin:17-jre-jammy`、`node:22-bookworm-slim`、`nginx:stable-alpine` 与 `postgres:17` 版本系列，标签可更新。本地验收记录保存实际镜像摘要；尚未提供生产镜像锁定、漏洞扫描或 Helm 发布流程。

## 验证

```bash
python3 scripts/check-system.py http://127.0.0.1:8180
python3 scripts/check-process-templates.py http://127.0.0.1:8180
```

第一个脚本只读业务数据并验证权限、来源、探针及自检；第二个脚本会保留带随机前缀的演示流程与申请，完成三个模板的真实审批路径。容器构建执行作者检查与编译，测试门禁仍使用 README 中的后端 `mvn verify`、前端测试与构建命令。

实现依据：[Docker Compose 启动依赖与健康检查](https://docs.docker.com/compose/how-tos/startup-order/)、[Spring Boot 3.5 Actuator 健康与探针](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)。
