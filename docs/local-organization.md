# 本地组织、任职与审批选人

2026-09-26，按已确认的“本平台维护组织，保留 OIDC 认证”实施。当前完成法人、部门、岗位、人员、多任职管理以及指定人员/部门/岗位选人；主管链、部门负责人和发起任职选择仍待业务规则确认，不视为完整组织工作包完成。

## 调用链与职责

管理员页面 `OrganizationDirectory` → `OrganizationController` → `OrganizationService` → 组织实体和 `OrganizationRepository`。入口只允许当前可信租户的 ADMIN，沿用统一 CSRF、页面身份绑定、幂等请求与事务。人员绑定身份源的稳定 sub，本地目录不创建密码、不授予系统角色、不更改 OIDC Actor。关系校验、部门环检测和审计写入由跨实体应用服务承担；实体负责自身启停、稳定绑定和修订转换。目录行锁串行化关系变更，同一事务追加审计并递增目录修订。

设计器 → `DefinitionAssigneeDirectory` → `LocalOrganizationDirectory` 提供可读的人员、部门和岗位选项。发布器只生成受控组织解析表达式，引用 UUID；任意 sub 不拼入表达式。引擎激活节点 → `FlowableOrganizationMembers` → `OrganizationAssigneeResolver`，按当前目录解析实际成员，在执行作用域保留规则、目录修订和当时成员。SINGLE 冻结候选用户，ALL 沿用原会签根执行的首次成员快照。无人可审批时回滚推进，不跳过审批。

任务列表、待办读模型与任务办理 → `TaskRecipientDirectory.eligible`，同时要求身份源的 APPROVER 角色以及本地有效审批资格。改任职影响后续任务，不覆盖已创建任务的责任名单；人员停用或取消资格立即阻止其后续读取/办理待办。已完成事实仍保留。当前只查询直接任职部门/岗位，不推导上级部门、递归成员或主管关系，也不提供级联停用。

显式初始化后，空目录不会回退为演示名单。初始化前保留原演示兼容行为。启用前应准备人员身份映射和待切换流程；既有演示角色规则不会自动转换为本地组织规则。

## 数据与页面

V26 新增 `organization_directory`、`organization_unit`、`organization_person`、`organization_appointment`、`organization_change`，跨表引用包含 tenant_id。身份主体唯一性限于租户；法人归属、人员主体及任职关系不能在修改中被替换。调岗采用停用旧任职、新增任职；所有变更保留原关系和历史快照。

管理页面按类型分页，使用修订号拒绝过期写入。草稿按账号隔离，未知写结果沿用原正文和幂等键恢复；晚到响应不能覆盖另一账号。独立历史和引用读取也有 12 秒超时，失败保留原记录及游标供重试。选人下拉显示实际目录名称，流程图缺少目录上下文时显示本地人员/组织类型，不展示技术角色标识。

## 验证与发现

证据位于 `/fyoung/tmp/agentflow-confirmed-rules-20260926`。以下范围有重叠，不相加为独立测试总数；本轮按改动范围回归，没有将历史全量结果冒充当前全量验收。

| 范围 | 结果 | 日志 |
| --- | --- | --- |
| 组织、任务委派、待办和幂等身份回归 | 69 项通过（领域 3、服务端 66） | `organization-identity-regression.log` |
| 组织、OIDC 授权码登录、审批/定义安全、SLA、V26 迁移 | 56 项通过 | `organization-security-regression.log` |
| PostgreSQL 组织管理与实际审批 | 10 项通过 | `organization-postgres.log` |
| PostgreSQL V25 → V26 保留数据与租户外键 | 1 项通过 | `organization-postgres-migration.log` |
| 已有契约及迁移回归 | 10 项通过 | `organization-contract-migrations.log` |
| 前端与静态契约 | 293 项通过；82 操作、131 模型、28 请求样例 | `organization-final-web-tests.log` |
| 独立 H2 实际 HTTP 契约 | 82 操作通过，覆盖 200/201/400/401/403/404/409/422 | `organization-openapi-runtime.log` |
| Vue/TypeScript/Vite | 构建通过，保留既有大包提示 | `organization-final-build.log` |
| Java 作者与语法 | 339 文件、526 命名类型，缺失及解析错误均 0 | `organization-authors.log` |

测试先复现并修复了四个边界：

- 动态改写认证角色会使目录初始化的幂等回放改变身份指纹。将资格检查放在任务授权层，保持认证角色不变。
- 旧任务列表没有显式检查 APPROVER，撤销角色后仍可能列出已指派任务。最小失败测试后补齐原入口缺口。
- 组织页面的历史和引用分页缺少独立超时。实际组件 setup 测试挂起请求得到失败，再补超时和取消。
- 选人组件忽略服务端目录标签，显示内部组织角色 ID。先失败测试，再修复标签使用。

浏览器通过隔离 H2 `18187` / Vite `5198` 实际创建法人、部门、岗位、人员和任职，查看审计，选择部门/岗位发布流程。随后 API 提交合成申请，manager 在浏览器收到本地部门候选任务并批准；API 复核为 APPROVED、版本 4、第 1 轮。`organization-browser-fixture.json` 记录对象与结果；`organization-browser-appointments.png`、`organization-browser-approved.png` 记录页面。企业认证测试使用本地签名 OIDC 提供方，不代表已接入真实企业 IdP。

## 仍待完成

多任职发起时如何选择责任上下文尚待确认，推荐发起时选择本次任职并固定至该提交轮次；不擅自指定主岗位。主管链、部门负责人和完整组织验收随该规则继续开发。字段权限/附件、财务外部契约、模型服务及数据授权、生产环境等也仍有独立依赖。Git 远端为空，尚无 GitHub PR 或远程 CI；当前源码在隔离开发分支，本地主演示未部署本轮变更。

## 2026-09-26 补充：申请人查看历史责任

申请详情 → 轮次流程图 → `FlowableRoundDiagramAdapter` 在完成原申请授权及精确轮次/实例绑定核验后，读取执行作用域的组织候选历史快照。对外仅返回快照标识、目录修订和原候选账号，不返回内部选人规则，也不查询当前目录补写历史。无需新增迁移或改动原组织快照。

轮次流程图点击人工节点后展示“节点初始候选账号”。它描述激活时的候选范围，不代表当前资格、领取/转交后的办理人或最终批准人。未到达节点和未保存快照的旧任务分别说明缺失事实，空证据不会转换成“没有审批人”。

原测试只断言引擎变量被冻结，没有从申请人 API 检查是否可见。最小测试先因候选快照缺失失败，修复后验证：原轮次退回后更改任职再重提，第一轮旧名单不变、第二轮保存新名单；全员会签结束后原名单仍可读；无关人员和其他租户均不能读取；既有演示任务不伪造组织快照。

验证位于 `/fyoung/tmp/agentflow-responsibility-20260926`：Java 18 项范围回归与打包通过；PostgreSQL 5 项组织审批测试通过；前端 293 项、82 操作/132 模型静态契约及构建通过。真实 HTTP 读取上一版本已批准的合成申请，响应符合更新契约，读前后申请完全一致（APPROVED/v4），匿名返回 401。Chrome 以原申请人 alice 读取相同申请，部门审批显示原候选 manager，未到达的财务节点显示无记录。截图 `browser-candidates.png`。证据索引见 [历史责任验收](evidence/organization-responsibility-20260926.json)。测试服务停止后保留数据库和证据；主工程及主演示不变。
