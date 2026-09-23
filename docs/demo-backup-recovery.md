# 演示数据库备份与隔离恢复

工具适用于仓库 `compose.demo.yml` 启动的 PostgreSQL 17 演示实例。它备份整个 `agentflow` 数据库，将业务记录、审批轮次、幂等记录和 Flowable 运行/历史表一起恢复到新项目、新卷及新端口；原实例继续运行。

这属于部署工具，不进入 DDD 领域或业务 API。调用方是本机维护者，下游是 Docker、PostgreSQL 原生工具及 Compose，影响场景是演示数据保护、升级前验证和恢复演练。应用仍通过原审批链路办理任务，不以数据库脚本模拟审批结果。

## 使用前提

- 宿主机安装 Python 3.9+，Docker 正常运行，Compose 支持 `up --wait`。Python 仅使用标准库。
- 使用标准服务名 `<项目>-database-1`、`<项目>-server-1`、`<项目>-web-1`，数据库用户及库名为 `agentflow`，演示认证开启；自定义生产部署和 H2 不在此工具范围内。
- 备份期间保持部署配置不变，不并发升级、迁移或替换容器。普通审批可以继续写入；备份只包括其数据库快照中的已提交内容。
- 保留对应版本的代码和三个镜像。清单记录的是本机不可变镜像 ID，恢复要求它们仍在本机；不会下载同名新标签，也不包含镜像包。
- 备份路径的父目录须已存在，每次使用新的输出目录。输出目录权限为 `0700`，归档和清单为 `0600`；备份未加密，按业务数据管理。

## 备份与校验

在仓库根目录执行；示例目录须尚不存在：

```bash
python3 scripts/demo_database.py backup \
  --project agentflow-demo \
  --output /fyoung/tmp/agentflow-backup-example

python3 scripts/demo_database.py check \
  --bundle /fyoung/tmp/agentflow-backup-example
```

`BACKUP_COMPLETE` 表示 `pg_dump` 成功且 `pg_restore --list` 可读取归档目录。`manifest.json` 最后写入，记录来源、PostgreSQL 版本、镜像 ID、长度和 SHA256；只有半份 `database.dump` 不能恢复。如果出现 `dump-diagnostics.log`，同时检查其中的备份诊断。

`CHECKSUM_VALID` 只证明归档与清单一致，明确返回 `restoreTested: false`。哈希不能证明备份来源可信，也不能代替恢复演练。只恢复自己管理的可信备份；归档可能包含可执行 SQL。

## 恢复到独立实例

```bash
python3 scripts/demo_database.py restore \
  --bundle /fyoung/tmp/agentflow-backup-example \
  --project agentflow-recovery-example \
  --port 8281 \
  --output /fyoung/tmp/agentflow-recovery-example
```

恢复前先校验归档，再拒绝源项目、默认主项目、已有容器/卷/网络和占用端口。新数据库先在无网络、无宿主机端口的临时容器中启动，用单事务执行恢复；失败不会启动应用，也不会执行 `--clean`、删库或删除卷。

恢复后检查必需业务表、Flowable 表及成功的迁移记录，记录公共表的行数与内容摘要，再停止临时数据库，用备份时的镜像启动新实例。只有数据库、后端和 Web 都健康，才写 `receipt.json` 并返回 `RESTORE_COMPLETE`。

打开回执中的地址，用原演示账号重新登录。内存登录令牌不包含在数据库备份中。应核对旧申请、已结束轮次、当前待办、评论及审计，再用允许办理的演练申请完成一次审批。恢复出的业务历史仍受原申请权限控制。

`database-inventory.json` 记录的是应用启动前各表的数量和摘要，便于比对；它不是启动后数据库不变的承诺，也没有把恢复副本与仍在写入的源库自动判为相等。完整数据库备份由 PostgreSQL 的一致快照保证，不能用逐表导出代替。

## 管理恢复实例

后续管理必须同时带项目名、原 Compose、生成的覆盖文件及原端口：

```bash
AGENTFLOW_DEMO_PORT=8281 docker compose \
  -p agentflow-recovery-example \
  -f compose.demo.yml \
  -f /fyoung/tmp/agentflow-recovery-example/compose.restore.json \
  ps

AGENTFLOW_DEMO_PORT=8281 docker compose \
  -p agentflow-recovery-example \
  -f compose.demo.yml \
  -f /fyoung/tmp/agentflow-recovery-example/compose.restore.json \
  stop

AGENTFLOW_DEMO_PORT=8281 docker compose \
  -p agentflow-recovery-example \
  -f compose.demo.yml \
  -f /fyoung/tmp/agentflow-recovery-example/compose.restore.json \
  up -d --no-build --no-recreate --wait --wait-timeout 180
```

新数据卷 `<项目>_demo-postgres` 在覆盖文件中声明为外部卷。演练后停止实例并保留卷、输出和已停止的 `<项目>-restore-seed` 容器。不要为了重试删除已有数据；另选项目、端口和输出目录进行下一次恢复。

## 失败判定与排查

| 结果 | 处理 |
|---|---|
| `OUTPUT_EXISTS` | 输出不覆盖；使用新的目录 |
| `INVALID_BUNDLE` / `CHECKSUM_MISMATCH` | 归档不完整或改变；从可信完整备份重新开始 |
| `SOURCE_TARGET_CONFLICT` / `TARGET_EXISTS` | 保留原项目及已有资源，另选新项目 |
| `PORT_IN_USE` | 另选未使用的本机端口 |
| `TARGET_RACE` | 目标卷由另一操作创建，未用于恢复；检查并发操作后另选项目 |
| Docker、数据库或健康检查失败 | 查看输出中的 `failure.json`、可选的 `diagnostics.log` 及 `intent.json`；不要把无回执的操作视为恢复完成 |

输出及资源在失败后保留，不自动恢复执行、不自动清理。若在应用启动阶段失败，可能已经有本次独立应用容器运行；根据 `intent.json` 的项目和上面的完整 Compose 参数检查或停止该实例。`diagnostics.log` 可能含数据库对象信息，不应公开发布。

## 验证与边界

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s scripts/tests -p 'test_demo_database.py' -v
```

真实演练脚本仅用于允许写入合成数据的隔离本机实例：

```bash
# 在第一个恢复副本生成退回重提、部分会签及评论，再对这个副本备份。
python3 scripts/check-demo-recovery.py prepare \
  http://127.0.0.1:8281 /fyoung/tmp/recovery-fixture-example.json --exercise

# 将上述新备份恢复到第二个副本后，验证历史、待办、幂等回放与无关用户权限。
python3 scripts/check-demo-recovery.py verify \
  http://127.0.0.1:8282 /fyoung/tmp/recovery-fixture-example.json --exercise

# 在第二个副本页面用 admin 批准剩余会签任务，然后核对结束状态和旧轮次。
python3 scripts/check-demo-recovery.py finish \
  http://127.0.0.1:8282 /fyoung/tmp/recovery-fixture-example.json --exercise
```

脚本保留测试申请及状态文件，不保存登录令牌。不要对主实例执行 `prepare`。阶段验收已实际完成两次独立恢复和一次恢复后继续会签；未验证生产大库容量或故障恢复时限。

当前不包含自动备份计划、远端存储、加密与签名、全局数据库角色、文件对象存储、WAL/PITR、跨主版本升级或生产切换，不声明生产 RPO/RTO。一次演示恢复成功也不表示已完成升级回滚或生产灾备。

实现依据：[PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html)、[PostgreSQL 17 pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)、[Docker volumes](https://docs.docker.com/engine/storage/volumes/)。
