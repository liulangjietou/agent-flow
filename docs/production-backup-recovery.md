# 生产 PostgreSQL 备份与隔离恢复

`python3 scripts/production_database.py` 使用固定摘要的 PostgreSQL 17 客户端容器，通过证书与主机名校验连接外部数据库。备份包含整个数据库的逻辑快照；恢复只创建随机命名的新数据库，完成后提供回执，不自动启动应用、worker 或切换流量。

这是数据库恢复能力。已启用单机附件的部署必须按[数据库与附件配套恢复](field-attachments.md#数据库与文件配套恢复)使用 `scripts/production_attachments.py`，停止全部写入后配套备份、逐文件校验并恢复到新库和新目录。单独恢复本文的数据库归档不会恢复附件原文件。企业配置、镜像、密钥、角色授权、异地副本、RTO/RPO 和完整灾备切换仍须纳入部署恢复计划。

## 调用链与职责

运维 CLI → 固定 PostgreSQL 客户端容器 → 外部 PostgreSQL。工具编排备份文件、原生归档校验和新库恢复，不修改审批领域、Flowable 状态机或现有数据库结构。`backup/check/restore` 适用于 H2 之外的 PostgreSQL 17；原[演示恢复工具](demo-backup-recovery.md)保留自己的部署约束。

## 准备

- Linux 或 macOS、Python 3.10+、Docker 和当前操作者可写的 `/fyoung/tmp`。临时密码文件只在该目录下创建，调用结束后移除；输出目录由 `--output` 明确指定。
- 已验证、固定摘要的 PostgreSQL 17 客户端镜像。配置可以使用本地 `sha256:...` 镜像 ID，或 `registry/name@sha256:...` 仓库摘要；不接受浮动标签，运行时还会检查实际 pg_dump 主版本。
- 数据库可信 CA、与证书一致且容器可解析的主机名、Docker 网络。所有数据库连接强制 `sslmode=verify-full`，没有关闭校验的参数。
- 备份账号需有读取全部数据库对象的权限；恢复账号需能连接指定维护数据库并创建新库。角色、所有权和 ACL 由目标环境重新配置，不从归档原样复制权限。

配置文件仅包含定位与文件路径，例如：

```json
{
  "host": "database.example",
  "port": 5432,
  "database": "agentflow",
  "schema": "public",
  "username": "backup_operator",
  "passwordFile": "/etc/agentflow/database-backup-password",
  "caFile": "/etc/agentflow/database-ca.pem",
  "network": "agentflow-production_default",
  "clientImage": "postgres@sha256:REPLACE_WITH_VERIFIED_DIGEST"
}
```

必须替换镜像摘要占位值。`schema` 指定用于校验必要业务表的 schema，支持字母/下划线起始、最长 63 位的常规标识符；实际归档仍包含数据库全部 schema。恢复按归档记录的 schema 校验，不用目标配置覆盖它。

密码文件是一行原始值，允许结尾换行；冒号和反斜杠会按 libpq 规则转义。密码通过临时 `PGPASSFILE` 只读挂载，不进入命令参数、容器环境或终端输出。客户端容器使用操作者的 UID/GID，保持临时凭据 0600，在 Linux 上也无需放宽读取权限。CA 同样只读挂载，必须对该操作者可读；密钥文件及父目录应按部署账号限制访问。

## 备份与校验

```bash
python3 scripts/production_database.py backup \
  --config /etc/agentflow/backup-source.json \
  --output /secure-backups/agentflow-20260924

python3 scripts/production_database.py check \
  --bundle /secure-backups/agentflow-20260924
```

输出目录必须尚不存在，父目录必须已准备好。归档固定为 `database.dump`，自定义格式、一次 pg_dump 的一致快照；目录解析成功后才写 `manifest.json`。目录为 0700，归档、清单和诊断为 0600。归档包含业务和会话敏感数据，留存介质加密与异地保存应按企业存储策略执行。

清单记录格式、源库、业务 schema、服务器版本、归档长度、SHA-256、客户端镜像摘要和生成时间。`check` 离线检查固定文件名、常规文件、长度、文件头和摘要，不连接数据库；摘要校验不证明归档来源可信。只有自己或受信备份流程产生的归档才可进入恢复流程，因为 PostgreSQL 恢复会执行归档里的数据库定义。

备份期间禁止并发结构迁移。pg_dump 能在业务写入期间取得一致数据库快照，但升级回退还需按[停机升级流程](production-database-lifecycle.md)停止所有写入方并记录外部系统的边界；数据库快照不能代替预算、付款、对象存储和 Webhook 接收方的恢复点。

## 隔离恢复

目标连接配置可以指向另一台 PostgreSQL 17 服务器的维护库。下面命令不会写入配置中指定的现有数据库：

```bash
python3 scripts/production_database.py restore \
  --config /etc/agentflow/restore-target.json \
  --bundle /secure-backups/agentflow-20260924 \
  --output /secure-backups/recovery-check-20260924
```

工具先保存 `intent.json`，再创建 `agentflow_restore_<随机 ID>` 数据库。创建使用服务端原子拒绝重名的 CREATE DATABASE，不接受覆盖、drop、clean 或复用已有目标。归档以单事务、遇错即停方式导入；必要业务表及成功迁移记录检查通过后才写 `receipt.json`，状态为 `DATABASE_RESTORED`。

回执只证明数据库导入及结构检查完成。应用接入前还要：

1. 使用备份对应的原发布 jar 执行 `--schema=validate`，重新配置运行账号对表和序列的授权。
2. 核对历史单据、待办、审批轨迹、迁移版本及外部文件；在隔离入口完成原待办继续办理。
3. 按恢复计划处理已恢复会话及身份撤销记录，重新登录；核对 Webhook、财务和其他外部操作的去重/对账状态，再启用 worker。
4. 验证目标部署及恢复点满足业务要求后再切换入口。保留原数据库和镜像，不用替换 jar 代替数据库回退。

应用和写入方不会由本工具自动启动。失败的随机目标库和输出目录保留，便于核查；下一次恢复会选择另一个新库。既有库是否清理由独立维护流程决定。

## 返回与失败处理

成功退出 0，输出一条 JSON 状态：`BACKUP_COMPLETE`、`BUNDLE_VALID` 或 `DATABASE_RESTORED`。受控失败退出 1，输出稳定 `errorCode`；参数用法错误退出 2。原始数据库诊断只写私有文件。

备份失败不会写完成清单；恢复失败不会写成功回执。单个客户端超过 30 分钟后，工具只尝试停止自己本次命名的客户端容器，并记录停止是否确认；目标数据库始终保留。检查诊断、意图文件和对应容器状态后再决定下一次操作，不把超时当作数据库没有产生任何变化。

## 验收

自动测试覆盖配置/凭据约束、归档损坏、输出保护、创建竞争、恢复失败、私有诊断、超时客户端归属、自定义 schema 和客户端版本参数。真实 PostgreSQL 验收包括完整表/序列对照、待办保留与恢复后批准、错误证书、无建库权限、自定义 schema。证据见[阶段验收数据](evidence/production-backup-20260924.json)。生产目标环境、异地副本和 RTO/RPO 尚未验收。

技术依据：[PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html)、[pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)。实际行为以固定客户端镜像与本项目验收记录为准。
