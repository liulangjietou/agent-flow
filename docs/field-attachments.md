# 表单附件、字段权限与轮次留存

2026-09-28，按已确认方案实现单机持久目录附件：原文件读取跟随字段权限；申请人仅在草稿、退回或撤回时移除当前引用；提交固定轮次引用，历史原文件保留，不自动物理删除。此能力已在隔离开发分支验收，尚未合入 main 或升级主演示。

## 调用链与职责

调用方为申请详情、审批工作台及历史轮次中的 `AttachmentField`。浏览器先登记文件名、大小、SHA-256 和字段路径，再传输二进制正文；后端入口复用登录、OIDC CSRF、主体绑定与 JSON 登记幂等机制。

`AttachmentService` 编排申请授权、数据库元数据与文件 I/O；`Application` 判断可编辑状态及乐观锁版本；`Attachment` 判断完成状态与内容指纹；`AttachmentReferences` 从真实附件字段收集引用。文件不由 Flowable 保存，领域模型不依赖主机目录。数据库仓储绑定租户、申请和字段，`LocalAttachmentStore` 负责限长、校验、私有文件及不可覆盖的内容发布。

受影响场景包括普通附件字段、明细表附件列、草稿保存、退回或撤回补正、再次提交、审批人查看、管理员查看及历史下载。V30 增加附件元数据和轮次引用表，旧申请不会补造文件或引用。

## 实际操作与权限

1. 在表单设计器选择「附件」，可配置必填、敏感和各节点只读、隐藏或脱敏。明细表列也可使用附件。
2. 先保存申请草稿，再在详情选择文件。上传后保存当前引用；未完成的上传可以保存草稿，但不能提交审批。
3. 中断后使用「重试本次上传」或选回同一原文件恢复。文件 ID、大小和摘要不变；改变内容须另行登记。
4. 提交时检查实际提交聚合内的全部引用与文件完整性，并在同一事务冻结轮次。任何检查失败都会回滚申请、Flowable 实例和轮次记录。
5. 退回或撤回后可移除当前引用、添加新文件；旧轮次仍使用原内容和当轮权限。审批中不能上传或修改引用。

文件元数据与下载均使用服务端 `ApplicationFieldViews` 投影。隐藏字段不会返回文件名，脱敏字段也不能读取元数据或原文件；管理员受敏感字段限制。下载历史文件必须明确 `roundNo`，且该轮实际引用此文件。只知道 UUID、换用其他宽权限字段或其他租户均不能获得内容。申请人可在可编辑状态查询本申请尚未保存引用的上传，以恢复中断操作。

文件统一以下载响应返回，使用 `application/octet-stream`、`Content-Disposition: attachment`、`nosniff` 和 `no-store`；不公开存储目录、静态 URL 或匿名下载地址。当前没有内容扫描、在线预览、S3 或跨主机共享存储适配。

## 接口与约束

完整结构见 [OpenAPI](../agentflow-server/src/main/resources/api/openapi.json) 和产品 API 参考页。

| 方法及路径 | 行为 |
| --- | --- |
| `GET /api/v1/attachments/options` | 返回实际是否可用、容量限制与扫描能力 |
| `POST /api/v1/applications/{applicationId}/attachments` | JSON 登记；需要 `Idempotency-Key` 和 `expectedVersion` |
| `PUT /api/v1/applications/{applicationId}/attachments/{id}/content` | 原始二进制；需要 `X-Application-Version`，按已登记身份恢复 |
| `GET /api/v1/applications/{applicationId}/attachments/{id}` | 经过字段授权的元数据，可指定 `roundNo` |
| `GET /api/v1/applications/{applicationId}/attachments/{id}/content` | 经过同一字段授权及完整性检查的下载，可指定 `roundNo` |

附件值为规范 UUID 数组，每个字段或明细单元格最多 10 个，不允许重复。字段路径为 `proof` 或 `items.receipt`，与文件在登记时固定绑定。条件仅支持已填写和未填写，不对文件名、正文或 UUID 做业务比较。

默认单份 20 MiB，每申请累计 256 MiB、100 次登记。累计统计包括失败、未完成、移除引用及历史文件，不能通过移除引用回收额度；相同幂等登记的重放不新增一份。单份可配置上限最多 100 MiB，应用按实际读取字节限长，不信任 Content-Length。下载在有限内存中完整核对大小及 SHA-256 后才响应；并发下载的内存预算须包含单份上限。

二进制传输位于数据库事务外，发布前再锁定申请并复核状态及版本。内容落盘后用同目录硬链接排他发布，已发布内容不可覆盖。文件已发布而数据库确认丢失时，原 ID 重传核对原文件后可恢复 READY；并发失败不会将 READY 降级。

## 启用持久目录

未配置目录时保持关闭，不能默默写入容器临时层。直接运行 jar 时配置：

```dotenv
AGENTFLOW_ATTACHMENT_DIRECTORY=/srv/agentflow/attachments
AGENTFLOW_ATTACHMENT_MAX_FILE_BYTES=20971520
AGENTFLOW_ATTACHMENT_MAX_APPLICATION_BYTES=268435456
AGENTFLOW_ATTACHMENT_MAX_APPLICATION_UPLOADS=100
```

目录必须为绝对路径，位于支持 POSIX 权限及同文件系统硬链接的持久存储。目录由运行账号持有，权限 0700；原文件为 0600。应用启动会校验配置并收紧目录权限。系统自检检查元数据表和实际目录访问能力，不等同于执行写入探测或全目录完整性扫描。

容器部署先准备宿主机目录，设置所有者为服务器 UID/GID `10001`、权限 `0700`。在受控环境文件中设置 `AGENTFLOW_ATTACHMENT_HOST_DIRECTORY` 为该绝对路径，然后在原 Compose 命令中增加附件覆盖文件：

```bash
docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.attachments.yml config --quiet
docker compose --env-file /etc/agentflow/production.env \
  -f compose.production.yml -f compose.attachments.yml up -d --wait --wait-timeout 180
```

`compose.attachments.yml` 同样可与 `compose.demo.yml` 合用，显式绑定至 `/var/lib/agentflow/attachments`，不会代建缺失的宿主机目录。后续启动、停止和重建使用相同覆盖文件。多个实例只能在同一主机绑定同一物理目录；分布在不同主机的独立目录不支持此方案。

演示和生产 Nginx 仅对附件二进制内容路径放宽到 100 MiB，并关闭请求缓冲和上游自动重试；普通 JSON 仍保持 1 MiB 限制。浏览器读信息 12 秒、上传或下载 120 秒后释放界面等待，保留原上传身份用于重试；取消请求不能证明服务端没有完成写入。

## 数据库与文件配套恢复

启用附件后使用 `scripts/production_attachments.py`，它组合既有 [PostgreSQL 恢复工具](production-backup-recovery.md)。数据库连接继续使用固定摘要客户端和 `sslmode=verify-full`，密码只从受控文件读取。

必须先停止全部应用实例、worker 和上传写入，再执行：

```bash
python3 scripts/production_attachments.py backup \
  --config /etc/agentflow/backup-source.json \
  --directory /srv/agentflow/attachments \
  --output /secure-backups/agentflow-paired-20260928 \
  --writes-stopped
python3 scripts/production_attachments.py check \
  --bundle /secure-backups/agentflow-paired-20260928
python3 scripts/production_attachments.py restore \
  --config /etc/agentflow/restore-target.json \
  --bundle /secure-backups/agentflow-paired-20260928 \
  --output /secure-backups/agentflow-restored-20260928
```

`--writes-stopped` 是操作者对停写的明确声明，工具不会自动停实例或建立全局停写锁。输出目录必须全新。备份包含所有 READY 原文件，包括已移除引用的文件，也包含已经发布但数据库确认未完成的文件；没有原文的未完成上传与 `.part` 临时文件不计入完成清单。

顶层清单绑定数据库归档摘要与逐文件大小、SHA-256。缺失 READY 文件、内容损坏、链接、非法路径或前后元数据集合变化会阻止生成配套完成清单。离线 `check` 验证配套清单及内容；只有受信备份可恢复。

恢复只创建随机新数据库和私有新附件目录，再检查恢复库中全部 READY 引用及原文件。顶层 `receipt.json` 状态为 `DATABASE_AND_ATTACHMENTS_RESTORED` 后，才算数据库和文件配套恢复完成；内部数据库单独的回执不代表附件恢复成功。失败资源保留，不覆盖源库、不删除源文件、不自动启动应用或切换入口。

接续时按回执配置新数据库和新目录；容器运行前核对目录所有者与 UID `10001` 一致。使用对应发布包校验结构，重新登录，抽查历史权限与下载，再验证待办继续办理。生产目标环境、跨主机故障、异地副本和 RTO/RPO 仍需独立验收。

## 本地验收

自动回归覆盖登记幂等、上传中断及摘要错误、数据库确认丢失、上传与补正并发、提交的版本一致性、字段及租户隔离、容量、轮次保留、V29→V30 迁移、OIDC 二进制认证和跨域版本头。前端覆盖超时释放、迟到结果忽略、身份切换及重试恢复。

真实 HTTP 执行全部 91 个 OpenAPI 操作；真实 Nginx 完成 2 MiB 上传下载并确认普通 JSON 超限仍为 413。浏览器完成顶层及明细上传、主管可读/隐藏、管理员脱敏、撤回后移除引用与历史下载。TLS PostgreSQL 配套备份恢复 4 份文件，4 类业务表逐行一致；新实例重新提交并批准，原第一轮内容保持，文件摘要及权限保持。

各测试范围有重叠，不相加为唯一用例数。精确运行次数、回归红绿记录、日志及截图位置见 [机器可读证据](evidence/field-attachments-20260928.json)。远程 CI、真实企业身份源及生产环境不在本次本地验收范围。
