# 组织自检与交付说明核对

核对日期：2026-09-27。本次基于 `codex/governance-identifier-integration / 47c510a`，继续处理既有组织、流程治理及期限功能的交付遗漏。未改变多任职发起上下文或字段权限规则，完整平台目标仍未完成。

## 问题与根因

本地组织已经可以初始化、维护和用于审批选人，但系统自检仍把 `organization` 固定列入 `NOT_IMPLEMENTED`；OIDC 和通知说明也仍声称组织、SLA 未接入。首次使用引导及 README 延续了相同旧描述。

原测试只断言占位项数量，甚至要求组织状态继续为 `NOT_IMPLEMENTED`；组织管理测试没有读取系统自检。新增跨模块测试通过真实组织初始化 API 建立目录，再读取自检，修复前稳定失败：预期 `UP`，实际 `NOT_IMPLEMENTED`。独立 HTTP 验证还发现旧 `scripts/check-system.py` 固定要求九项检查，已经漏掉共享会话检查；其旧版脚本在当前十项报告上失败。

## 调用链与修复

系统自检页及首次使用引导 → `GET /api/v1/system/checks` → `SystemCheckService` → `SystemDiagnostics` → 当前租户的 `organization_directory` 启用记录。

诊断层已有真实数据库、通知和共享会话探针，因此组织启用状态在这一层以参数化只读查询取得，继续使用有界执行器、三秒 SQL 超时、统一错误脱敏及入口 ADMIN 授权。没有新增组织状态副本、迁移或业务写入。

- 已启用：`UP / LOCAL_ORGANIZATION_ENABLED`，只证明目录启用记录可读；人员、任职与审批资格仍需核对。
- 未启用：`WARNING / LOCAL_ORGANIZATION_NOT_INITIALIZED`，提示管理员使用已有组织管理入口。
- 查询失败或超时：沿用 `DOWN / CHECK_FAILED` 或 `UNKNOWN`，不会误报为未初始化，也不暴露异常原文。

OIDC 检查只描述身份配置，消息检查只描述存储查询，不推断组织已经就绪或超时调度正在运行。UI、验收脚本及使用手册同步采用这些边界。历史配置阶段的测试与部署记录保留其原日期；当前状态说明明确指出组织、重提守卫和实际 SLA 已在开发分支实现。

## 本次验证

证据目录：`/fyoung/tmp/agentflow-system-status-20260927`，文件摘要见[证据索引](evidence/system-diagnostics-20260927.json)。

| 范围 | 结果 |
|---|---|
| 修复前最小集成测试 | 1 项失败，准确复现已初始化目录被标记为未实现 |
| 后端范围回归 | 30 项通过：组织管理、自检、首次引导和 OpenAPI 契约；无失败、错误或跳过 |
| 授权与数据边界 | 跨租户未初始化状态独立；目录、人员及审计读前后不变；人员主体、标识及内部异常不进入响应 |
| 前端范围回归 | 自检和首次引导现有 9 项通过，覆盖请求取消、账号切换、迟到响应和只读请求 |
| 静态契约及构建 | 82 操作、132 模型、28 请求样例校验通过；Vue/TypeScript/Vite 构建通过，保留既有大包提示 |
| 真实 HTTP | 独立 H2 服务 `18188`，未初始化和初始化后两次验收通过；401/403、来源拒绝、探针和业务只读检查通过 |
| 文档与源码检查 | Java 作者检查、修改文件的本地文档链接及 `git diff --check` 通过 |

范围回归命令：

```bash
mvn -B -ntp -pl agentflow-server -am \
  -Dtest=SystemCheckServiceTest,SystemChecksIntegrationTest,OrganizationIntegrationTest,FirstWorkflowIntegrationTest,OpenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

测试只使用隔离 H2 数据和演示身份；本轮未重复 PostgreSQL、浏览器或完整项目测试，也不替代此前各功能的验收。真实 HTTP 测试服务结束后停止，数据库与证据保留。

## 剩余交付与业务依赖

`main` 仍为 `60bafa0`，主演示未在本轮变更；组织与治理成果尚未合入主线。Git 远端为空，没有 PR 或远程 CI，开发工作树继续保留。

下一步仍需明确多任职发起上下文及字段权限。附件存储与留存、财务制度与外部接口、模型服务与数据授权、真实企业身份和生产环境、其他高级流程及外部通知仍有独立待定事项。本次修复只完成组织自检及相关交付说明，不把完整平台标记为完成。
