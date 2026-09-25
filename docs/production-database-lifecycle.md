# 生产数据库初始化与升级

同一发布 jar 提供独立 PostgreSQL 结构命令。命令不启动 HTTP、身份服务、Webhook 调度或示例流程；`prod` 应用进程只检查数据库是否与当前发布包一致，不再自动执行 Flyway 迁移。

本功能补齐数据库部署步骤。企业身份、组织目录、完整安装向导、生产高可用与灾备验收仍未完成，不能将迁移成功视为整个平台可以上线。

## 配置与命令

由部署系统注入三个环境变量：

| 环境变量 | 用途 |
|---|---|
| `AGENTFLOW_DATASOURCE_URL` | 明确的 `jdbc:postgresql://host:port/database` 地址；非默认 schema 可在 URL 指定 `currentSchema` |
| `AGENTFLOW_DATASOURCE_USERNAME` | 目标库账号 |
| `AGENTFLOW_DATASOURCE_PASSWORD` | 目标库凭证，通过部署密钥注入 |

变量缺失、密码为空、非 PostgreSQL 地址或其他参数会直接拒绝。命令仅使用这些数据库变量，不读取 Spring 配置文件，不接受迁移目录、target、clean、repair 或 baseline 参数。发布包自带的全部 `db/migration` 是业务结构的唯一来源；凭证不要写进命令行、版本库或截图。

```bash
java -jar agentflow-server.jar --schema=migrate
java -jar agentflow-server.jar --schema=validate
```

使用现有服务器镜像时，可在目标数据库可达的网络中执行一次性容器；`server-image:release` 是替换为实际发布版本的占位标签：

```bash
docker run --rm --network agentflow-production \
  -e AGENTFLOW_DATASOURCE_URL -e AGENTFLOW_DATASOURCE_USERNAME -e AGENTFLOW_DATASOURCE_PASSWORD \
  server-image:release --schema=migrate
```

镜像入口已包含 `java -jar`，不要再重复添加它。命令正常结束后进程退出，不监听服务端口。

## 首次安装

1. 创建独立 PostgreSQL 数据库及目标 schema，配置可连接的数据库账号。迁移账号需要目标 schema 的建表、索引及结构修改权限。目标 schema 中不要放置其他系统的表。
2. 固定并核对发布 jar 或镜像版本，注入数据库变量，执行 `--schema=migrate`。先运行 Flyway 业务迁移，再由所锁定的 Flowable 库初始化引擎结构，最后检查二者。
3. 执行 `--schema=validate`，必须退出 0。它使用强制只读连接，不修复校验和，不建表，不部署流程。
4. 按[企业 OIDC 配置](enterprise-oidc.md)准备 HTTPS Origin、身份服务、明确的租户和角色映射；使用 `SPRING_PROFILES_ACTIVE=prod` 启动应用。多实例会话配置见[共享会话](shared-enterprise-sessions.md)。
5. 核对 readiness、实际认证和业务授权。空库没有示例流程或组织；初始化租户、组织和审批人仍需后续产品能力，不能仅凭健康探针认定业务可用。

正常应用账号需要业务、引擎及会话表的读写权限，但可以不给 DDL 权限。迁移账号与运行账号分离时，应由 DBA 配置表、序列及后续新增对象的授权；工具不会创建数据库账号或自动授权。

## 停机升级

1. 在代表性数据库副本上执行新版本迁移，核对历史单据、待办继续办理及接口兼容；Flowable 依赖变更必须重新完成该演练。本阶段验证当前 7.2.0 引擎及 V22 → V23 业务结构，不承诺未经演练的引擎跨版本升级。
2. 进入维护窗口，停止所有应用节点、独立 worker 和其他数据库写入方。工具的 PostgreSQL advisory lock 只互斥本工具命令，不能代替停机，也不会自动终止在线服务。
3. 使用[生产备份与隔离恢复工具](production-backup-recovery.md)保存整个 PostgreSQL 数据库，同时保留原配置与应用镜像。备份中包含业务和会话敏感数据，应按企业制度限制访问；原演示备份工具继续只适用于演示部署。
4. 使用新版本发布包执行 `--schema=migrate`，再执行 `--schema=validate`。任何非零退出均停止后续发布。
5. 启动新版本 `prod` 服务并完成 readiness、认证、原待办继续办理与历史记录核对，再恢复入口。

业务迁移与引擎迁移不是一个整体事务。中途失败时保留日志与数据库现场，检查失败阶段；已提交的 Flyway 迁移不会在再次执行时重做。不能直接改校验和、运行 clean/repair，或只替换旧 jar 当作数据库回滚。确需回退时，将备份恢复到隔离数据库并配套使用原版本，核对一致性后再切换。

## 生产启动约束

- `prod` 保持 Flyway bean 及其依赖顺序，但将启动策略替换为完整发布包校验。空库、待执行迁移、校验和变化、未知未来迁移均拒绝启动。
- 运行配置中的 Flyway target 或 locations 不能隐藏当前发布包需要的迁移。
- `flowable.database-schema-update=false`、`flowable.check-process-definitions=false` 必须保持；不能关闭 Flyway 来绕过校验。
- 业务与引擎都使用连接的当前 schema；显式 Flowable schema 与其冲突时拒绝启动。配置 `currentSchema` 时应只指定一个实际存在的目标 schema。
- 开发和演示模式继续原来的自动初始化行为，本改动不升级其数据库。

结构检查核对迁移历史和引擎版本，不是完整的数据库篡改检测。禁止手工修改已发布迁移和生产表结构。

## 返回值与排障

| 退出码 | 含义 |
|---|---|
| 0 | 迁移或校验成功，业务与引擎结构检查通过 |
| 2 | 命令、地址或必需环境变量不合法，未连接目标库 |
| 1 | 连接、互斥或结构操作失败 |

失败输出包含稳定错误码 `DATABASE_SCHEMA_FAILED`、`phase` 和异常类型，不输出 JDBC 地址、用户名、密码或原始 SQL 异常。`connect` 检查网络和凭证；`lock` 表示另一个结构命令占用互斥锁；`validate` / `migrate` 检查迁移历史、数据库服务日志、发布包版本和表权限。默认连接等待 10 秒，JDBC socket 等待 60 秒；大规模迁移须先在副本上评估执行时间。

Flowable 7.2.0 默认引擎关闭回调会清理运行锁。本命令使用专用配置关闭该回调，仍关闭引擎资源；迁移引擎从未执行任务，因此不能替运行节点清理锁。这是对固定依赖的适配，升级依赖时必须重跑 PostgreSQL 只读检查。

## 验证

常规 `mvn verify` 覆盖空库/旧库拒绝、成功启动、配置绕过、迁移重入、旧任务继续办理、篡改及未知版本。PostgreSQL 使用独立数据库执行：

```bash
# 由测试环境注入 AGENTFLOW_SCHEMA_TEST_URL / USERNAME / PASSWORD。
mvn -B -ntp -pl agentflow-server -am \
  -Dtest=DatabaseSchemaPostgresIT -Dsurefire.failIfNoSpecifiedTests=false test
```

测试 URL 不带查询参数，测试会在该库中创建随机 `schema71_*` schema；仅用于隔离库，数据保留供排障。GitHub 工作流增加了对应 PostgreSQL 17 job；是否实际执行远程 CI 以仓库交付记录为准。

技术依据：[Flowable 数据库配置](https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration)、[Flyway 校验语义](https://documentation.red-gate.com/flyway/reference/commands/validate)。具体行为以项目锁定的 Flowable 7.2.0、Flyway 11.7.2 及实际数据库验证为准。
