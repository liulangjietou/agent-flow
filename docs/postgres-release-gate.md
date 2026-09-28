# PostgreSQL 发布门禁与 V29 验证

本文下方记录 V29 发布包的历史验收。附件实施随后新增 V30，并在同一 CI 业务门禁追加 `AGENTFLOW_ATTACHMENT_TEST` 和 `AGENTFLOW_ATTACHMENT_MIGRATION` 两个独立数据库，分别运行附件生命周期及 V29→V30 迁移；当前配置共 9 个业务测试库。V30 的实际运行与配套恢复结果见[附件证据](evidence/field-attachments-20260928.json)，不复用下方 V29 jar 的摘要作为新发布包证明。两个 CI 作业都在 Java 测试之前准备 `/fyoung/tmp`。

2026-09-28，在隔离工作树完成当前 V29 发布包的 PostgreSQL 17.11 验证，并将组织、字段权限、待办及运营查询的 PostgreSQL 回归接入现有 CI 配置。本次只修改测试、工作流和文档，没有新增业务规则或数据库迁移。远程 CI 尚未执行。

## 升级测试修复

调用链为 `.github/workflows/ci.yml` 的 `postgres-schema` 作业 → `DatabaseSchemaPostgresIT` → `DatabaseSchemaCommand` → `DatabaseSchemaLifecycle` → Flyway 和 Flowable。测试负责检查发布包初始化、只读校验、升级及生产启动；迁移逻辑继续留在既有结构生命周期服务中。

旧测试先在 V22 插入草稿和引擎任务，再升级到当前发布包。它将升级前后的 `SELECT *` 结果直接比较，但 V24 已为申请新增 `notification_texts_json`。首次执行 5 项中 1 项失败，差异仅为新增列 `null`，并非旧值被改写。该 `*IT` 需要明确指定 PostgreSQL 环境和测试类，默认 H2 测试不会执行它；此前 V26–V29 的专项迁移验证也没有运行这条 V22 历史夹具断言。

修复后的预期值保留全部旧字段，仅明确增加 `notification_texts_json=null`，仍严格比较行数、原值和新增列。没有忽略未知列或弱化为局部字段检查。5 项重跑全部通过，升级前的引擎任务保持，并能在升级后完成。

## 本地验证结果

| 范围 | 实际结果 |
| --- | --- |
| PostgreSQL 结构生命周期 | 5 项通过：空库初始化及强制只读校验、V22→V29 保留旧数据/任务、迁移互斥锁、校验和异常拒绝且不修复、`prod` 启动不迁移或部署示例 |
| PostgreSQL 业务门禁 | 36 项通过：组织审批 11、组织与任职迁移 2、待办 12、运营 7、字段权限 3、轮次组织索引迁移 1 |
| H2 默认配置回归 | 修改了数据库配置入口的组织迁移 2 项、字段权限 3 项全部通过；与 PostgreSQL 用例重叠，不累加为唯一用例数 |
| 实际发布 jar | 在保留旧草稿的健康 schema 上依次执行 `--schema=validate`、`--schema=migrate`、`--schema=validate`，均退出 0；每步之后 73 张表的行数及排序后的行内容摘要保持 |
| 静态检查 | 工作流 YAML 解析、最终步骤 Bash 语法检查、Java author 检查和 `git diff --check` 通过 |

发布 jar 的业务源码来自提交 `0d506bb9316f3c50cc7c26d35d8685f6b63ced6f`，SHA-256 为 `308ab303c585c04d6e474861fa7cc3ff7de5db75411b82fe0dd84fa73b82dca9`。本次测试修改不影响该 jar。测试包含一个主动篡改校验和的独立 schema，用于证明命令拒绝且不修复；保留的该 schema 是预期失败夹具，未用于成功命令验证。

## 持续验证配置

现有 PostgreSQL CI 作业增加 7 个独立测试库。组织迁移测试分别停在 V26、V28，其他测试使用当前完整迁移；隔离库避免旧版本夹具与完整 Spring 上下文互相污染。

| 环境变量前缀 | 测试范围 | 本次实际迁移版本 |
| --- | --- | --- |
| `AGENTFLOW_ORGANIZATION_TEST` | 组织及动态审批人 | V29 |
| `AGENTFLOW_ORGANIZATION_MIGRATION` | V25→V26 组织结构 | V26 |
| `AGENTFLOW_CONTEXT_MIGRATION` | V26→V28 任职与字段权限 | V28 |
| `AGENTFLOW_FIELD_TEST` | 字段权限及数据投影 | V29 |
| `AGENTFLOW_QUEUE_TEST` | 待办授权与筛选 | V29 |
| `AGENTFLOW_OPERATIONS_TEST` | 运营统计权限与筛选 | V29 |
| `AGENTFLOW_ROUND_ORGANIZATION_MIGRATION` | 轮次组织检索索引 | V29 |

工作流为每组注入 `_URL`、`_USER`、`_PASSWORD`，Spring 上下文测试另使用 `_DRIVER`。原有 Java system property 配置保持最高优先级，未提供配置时仍使用 H2。测试后逐库读取 Flyway 迁移版本并要求存在，确认 PostgreSQL 测试库确实被使用；具体迁移内容由测试断言校验，不在 CI 脚本重复硬编码当前最新版本号。

本地执行了工作流的建库和 Maven 命令，并在最终脚本改为通用版本存在检查后，单独对全部 7 个已填充数据库执行了最终检查。YAML 与 Bash 检查不能替代 GitHub runner 验证，当前无 Git remote，未声称远程工作流通过。

结构测试复现命令见[生产数据库生命周期](production-database-lifecycle.md#验证)，业务范围的命令及数据库准备步骤见[CI 配置](../.github/workflows/ci.yml)。仅对明确提供的隔离 PostgreSQL 库运行；凭证通过环境注入。

机器可读证据为 [postgres-release-gate-20260928.json](evidence/postgres-release-gate-20260928.json)。日志、失败与成功测试库均保存在 `/fyoung/tmp/agentflow-schema-v29-20260928` 及原 PostgreSQL 数据卷；本轮测试容器已停止，Docker Desktop 恢复启动前的停止状态。主工程、主演示和其他开发树未修改。

本记录只证明当前发布包及所列业务边界的本地 PostgreSQL 行为。真实企业认证、目标环境部署、容量和灾备等仍按[剩余工作](remaining-local-work.md)单独验收。
