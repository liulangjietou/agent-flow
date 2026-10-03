# V24 到 V26 发布包升级验证

2026-09-26，在独立 PostgreSQL 副本上验证通过。当前业务源码为 `9b521e9f30d6eb5dafa1109ab17484920a9cf8f5`，旧发布包对应 `d65b625`、V24。测试没有切换 main 或主演示 8180；不是生产企业身份、容量或灾备验收。

## 路径与结果

1. 从已停止的历史合成数据容器 `agentflow-identifier-postgres` 导出备份，恢复至新容器 `agentflow-v26-upgrade-20260926`。源库备份前后、恢复后均比较 67 张表的行数及排序后逐行 JSON 摘要，一致。
2. 旧发布包严格校验 V24 成功，在副本创建并提交申请，然后停止服务。该申请为 `2bbfb47e-d3f2-4472-bdd8-ee49bf2f2d4a`，流程 `identifier-old-running` 版本 1，第 1 轮。
3. 当前包执行 `--schema=migrate`，依次新增 V25、V26。66 张原业务／引擎表的全部原字段值一致，旧 Flyway 历史完整保留；定义新增 `start_enabled` 均为 true，5 张组织表及开关审计表为空，未虚构人员或历史操作。
4. 在 V26 上执行旧包 `--schema=validate`，返回退出码 1、`DATABASE_SCHEMA_FAILED`、`phase=validate`、`FlywayValidateException`。当前包校验和再次迁移返回 0；这三次命令前后 73 张表完全一致。
5. 当前包启动后，旧申请的流程版本、轮次、申请版本、状态与正文保持。原待办由 manager 成功批准，最终仍为流程版本 1、第 1 轮。运行使用 default profile 和 demo 认证；没有初始化本地组织，验证的是旧租户兼容路径。

## 发布约束

沿用[数据库生命周期步骤](production-database-lifecycle.md)：先备份，在停机／部署编排阶段迁移，再由严格校验启动服务。旧包严格校验已拒绝 V26；需要退回旧包时，应恢复匹配旧结构的备份到隔离库并验证，不能直接让旧包连接当前库。此前[default/demo 旧包绕过停用守卫的证据](governance-rollback-compatibility.md)仍有效，不能以演示模式能够启动替代兼容性判断。

本轮只补充发布兼容验证，无生产代码变化；未重复前一轮 Java、前端及浏览器回归。源库和新副本均已停止，HTTP 测试服务已退出，备份及数据保留。Git 远端仍为空，没有 PR 或远程 CI。

## 证据

[机器可读索引](evidence/v26-upgrade-20260926.json)包含发布包、备份、脚本及日志摘要。原始目录为 `/fyoung/tmp/agentflow-v26-upgrade-20260926/`，其中 `probe.py` 固定使用独立容器和端口；已有副本存在时会拒绝重建，重新验证应另选独立名称并保持原数据。
