# 当前未完成任务台账

更新：2026-10-03 UTC，F03、F10 已完整本地验收。已确认未完成降至 34 项。初次核对基线 `f5fd8d53c3d6bbafef456d96c462320bd186d8fc`，分支 `codex/governance-identifier-integration`。

F02 固定安装包完成报销挂账、借款挂账、付款凭证及原科目依据页面验收。缺项、错误回显、发布竞争、处理中重启、回执丢失原查询恢复通过；独立配套恢复保留 245 张表、6,276 行和 9 个附件。详见[完整本地证据](evidence/account-mapping-complete-20261003.json)。F13 三类模板与十个配套样例现已完成实际办理；核减路由、超标、借款冲销、事前消费、重复票阻断及免纸件模式通过。配套恢复后可接续在审单，见[完整模板证据](evidence/financial-template-journey-complete-20261003.json)。

**当前已确认 34 项未完成：16 项本地开发、3 项本地验收与核对、15 项需要真实企业环境或远端的联调/交付。全项目至少剩余 34 项。** V03 尚未完成，所以不能声称这是全量精确总数，也不能给完成百分比。

此前“6 项”是六个工作类别，不能当成六个小任务。本次发现原费用方案里的管理入口、补贴、跨单风险、额度控制、项目审批和报表仍有缺口，已单独编号；这些来自既定方案，没有新增产品需求。

计数单位是一个可独立验收的交付目标。同一功能的后台、页面、迁移和测试合算一项；本地实现与企业实际联调分开。已完成 OFD 组件属于 A01 的阶段进展，不把每个图元或测试计成新任务。

W01 的可信目录、公开设计及授权运行记录接口与页面已经接通。运行记录阶段 62 项 Java、39 项前端范围测试、构建和 OpenAPI 通过；固定包三轮强退前后均保留同一操作号，恢复时只查询原号，之后由人工审批完成。独立数据库与合成接收方的配套恢复也已通过，原号只查询不重发。真实浏览器和 PostgreSQL 新范围补测仍待完成，总数保持 34。见[配套恢复证据](evidence/service-task-paired-restore-20261003.json)、[运行记录阶段证据](evidence/service-task-runtime-view-20261003.json)、[公开设计阶段证据](evidence/service-task-public-design-20261003.json)和[实现说明](service-tasks.md)。

V02 的编号字符校验子问题已修复：草稿预检、保存和发布提前拦截非法流程/节点/连线编号；106 项 Java、36 项前端范围测试及固定包 42 次 HTTP 升级检查通过，旧草稿修正和原在审接续成功。见[修复说明](definition-identifier-syntax.md)。V02 整体验收仍为 OPEN，总数不变。

A02 后端与本人费用页面已接通，前阶段分别有 132 项 Java、118 项前端用例证据。固定包 V106→V107 保留 250 张旧表及 109 行；8 次启动完成实际断线后的原键恢复、执行中强退不重发、超时迟到与真实到期，以及排队后修改阻断。365 份实际响应中，解释接口 274 份通过契约及页面解析核对。浏览器与 PostgreSQL 补验仍待完成，A02 保持 OPEN。见[实施记录](precheck-explanation.md)、[页面阶段证据](evidence/precheck-explanation-ui-20261003.json)和[运行阶段证据](evidence/precheck-explanation-runtime-20261003.json)。

F17 已接通员工及供应商付款、凭证准备异常和原过账结果的通知、当前权限详情及消息页面。凭证阶段 238 项 Java、68 项前端用例、构建与 OpenAPI 检查通过；重新准备不替换旧消息，独立冲销绑定会明确显示原凭证停用。预算、核销及其他恢复来源、固定包和环境验收仍待完成，F17 保持 OPEN，总数仍为 34。见[凭证通知说明](voucher-notifications.md)和[凭证阶段证据](evidence/voucher-notifications-20261003.json)。

机器可读原表：[remaining-task-ledger.json](remaining-task-ledger.json)。原始规范为相邻 `doc/00` 至 `doc/05`，路径、SHA-256、规范行范围保存在原表；后附历史进度不再重复计为新需求。

## 数量与范围

| 分类 | 未完成数 | 主要内容 |
| --- | ---: | --- |
| Agent | 4 | OFD、模型预检解释、结构化财务填报、费用风险建议 |
| 组织与电子签 | 2 | 外部组织同步、电子签业务用例 |
| 费用与财务产品 | 9 | 跨单风险、额度、预算、路由、项目审批、报表、出纳及结果通知 |
| 流程能力 | 1 | 白名单服务任务 |
| 本地验收与核对 | 3 | 票据模型完整运行、最终版本验收、余下原条款核对 |
| 真实企业联调与交付 | 15 | 通知、财务、模型、组织、签署、IdP、部署恢复、容量、留存和 PR/CI |

## 逐项清单

### 本地开发

| 编号 | 待完成目标 | 当前事实 | 验收终点 |
| --- | --- | --- | --- |
| A01 | OFD 完整票据渲染与应用抽取入口 | 基础图元、模板、字体、静态批注、复合图元和隔离进程已有范围证据；公开抽取仍未支持 OFD。缺其余绘制能力、数字签章与嵌套内容、应用字体配置及全页模型输入。 [invoice-extraction.md:78](invoice-extraction.md#L78)、[InvoiceExtractionSources.java:1](../agentflow-server/src/main/java/io/agentflow/agent/InvoiceExtractionSources.java#L1) | 完整原件逐页对照；签章不能被静默丢弃；受控字体和资源限制经实际安装包验证；公开入口及完整页输入接通。签章图像呈现与签名有效性分别声明。 原依据：05 §4.1、§16；05 §16。 |
| A02 | 模型预检解释与补正建议 | 后端、本人费用页面及固定包 HTTP／升级／重启验收通过；浏览器和 PostgreSQL 新范围补验仍待完成。 [ExpensePrecheckEvaluator.java:1](../agentflow-server/src/main/java/io/agentflow/expense/ExpensePrecheckEvaluator.java#L1)、[draft-assist.md:3](draft-assist.md#L3) | 输入绑定当前预检及选定可读事实；解释带来源；过期结果不可采纳；建议不修改规则结论、金额或审批结果。 原依据：05 §16；04 §5。 |
| A03 | 结构化财务业务填报助手 | 普通草稿入口对 businessReference 非空明确返回 AGENT_DRAFT_UNSUPPORTED。票面金额辅助填报已完成，但没有按行程生成费用行、类别和分摊建议的专用入口。 [DraftAssistInputs.java:28](../agentflow-server/src/main/java/io/agentflow/agent/DraftAssistInputs.java#L28)、[expense-invoice-fill-20261002.json:1](evidence/expense-invoice-fill-20261002.json#L1) | 结构化业务建议走各自领域保存服务；报销行程、类别和分摊逐项人工确认；不由模型生成补贴标准、真实余额或批准事实；适用业务边界明确。 原依据：05 §16；04 §5。 |
| A04 | 费用异常的 Agent 风险提示 | 当前 ApprovalRiskPolicy 只评估本次表单白名单条件。没有费用领域同日多笔、节假日消费、连号等事实的专用模型提示与复核链路。 [ApprovalRiskPolicy.java:48](../agentflow-domain/src/main/java/io/agentflow/definition/ApprovalRiskPolicy.java#L48)、[agent-execution.md:1](agent-execution.md#L1) | 可解释地引用经授权的费用事实，保留提示和人工处置；不能凭模型标签自动驳回、核减或改变金额矩阵。确定性拆单路由由 F04 独立处理。 原依据：05 §16。 |
| I01 | 外部组织同步的本地完整用例 | 现有组织目录为可信管理员维护单位、人员、任职和关系；已实现 OIDC，但没有同步批次、来源映射、冲突处置及恢复入口。 [OrganizationController.java:1](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationController.java#L1)、[OrganizationService.java:1](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationService.java#L1) | 以单一可信源映射组织事实，预检和具名应用变更、停用及重试均有审计；不隐式创建认证身份或授予系统角色；旧审批轮次依据保持。 原依据：04 §3.A、§3.G；04 §3.G。 |
| I02 | 电子签业务端口、状态与操作页面 | 现有事件和支付 HMAC 验签解决消息完整性；未有文件签署业务用例。OFD 签章渲染也不等于电子签服务。 [product-goal-gap-audit.md:1](product-goal-gap-audit.md#L1)、[expense-archives.md:1](expense-archives.md#L1) | 固定文件与签署版本、显式授权、异步状态、可验证回执、重复回调和未知结果恢复；保留原件、授权及签署结果，完成本地受控协议验收。 原依据：04 §3.G；00 §6。 |
| F04 | 跨单拆单风险与合计金额路由 | 现有风险规则只读当前提交字段，没有同申请人、类别、时间窗口内跨单聚合及审批层级提升。 [ApprovalRiskPolicy.java:48](../agentflow-domain/src/main/java/io/agentflow/definition/ApprovalRiskPolicy.java#L48)、[ExpenseFormContract.java:1](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseFormContract.java#L1) | 窗口、阈值和启用状态显式配置；并发提交也按确定口径聚合；冻结风险及路由依据；不伪称未启用规则已经保护业务。 原依据：05 §5.3。 |
| F05 | 事前额度 STRICT/TOLERANCE/NONE 控制 | ExpenseRequest.ApprovedLine 能表达容差上限，但 ExpensePlan.approvedRequest 固定使用 BigDecimal.ZERO；没有按类别的模式、容差说明与额外审批配置。 [ExpensePlan.java:74](../agentflow-domain/src/main/java/io/agentflow/expense/ExpensePlan.java#L74)、[ExpenseRequest.java:134](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseRequest.java#L134) | 按已发布类别策略冻结控制模式和容差；超额说明与审批同步验证；并发占用、释放和关闭保持额度不变量。 原依据：05 §6.1。 |
| F09 | 柔性预算超支审批 | ExpenseBudgetOutcomeHandler 对 BUDGET_INSUFFICIENT 已实现自动退回；不存在区分柔性预算及预算负责人审批的路径，刚性不足不是缺失项。 [ExpenseBudgetOutcomeHandler.java:50](../agentflow-server/src/main/java/io/agentflow/expense/ExpenseBudgetOutcomeHandler.java#L50)、[ExpensePrecheckEvaluator.java:114](../agentflow-server/src/main/java/io/agentflow/expense/ExpensePrecheckEvaluator.java#L114) | 外部预算明确允许柔性策略才进入指定审批；例外不得伪造冻结成功；审批后重新确认预算，未知和拒绝状态保持阻断。 原依据：05 §8.1、§9；05 §9。 |
| F11 | 费用自审批场景自动上溯 | 通用职责分离已过滤申请人并在无人时阻断；多级主管可显式选择，但遇到费用自审批时不会自动沿任职关系上溯并审计。 [FlowableApprovalResponsibilities.java:85](../agentflow-server/src/main/java/io/agentflow/approval/process/FlowableApprovalResponsibilities.java#L85)、[OrganizationAssigneeResolver.java:107](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationAssigneeResolver.java#L107) | 仅已发布费用策略启用；固定任职依据、检测环路与空上级，记录原候选和替代人及规则版本；财务职责分离仍强制。 原依据：05 §9.1。 |
| F12 | 相邻业务审批人重复的受控自动通过 | 现有职责分离记录真实批准人以排除冲突，不实现相邻相同审批人自动通过；未有该类审计动作。 [FlowableApprovalResponsibilities.java:1](../agentflow-server/src/main/java/io/agentflow/approval/process/FlowableApprovalResponsibilities.java#L1)、[approval-responsibilities.md:1](approval-responsibilities.md#L1) | 按发布版本识别相邻业务节点，记录自动动作和来源；非相邻默认不跳过；财务签收、审核、复核永不自动跳过；并发和重启不重复推进。 原依据：05 §9.1。 |
| F14 | 项目分摊驱动的项目负责人会签 | FinanceCatalog.Project 只有法人、代码、名称；ExpenseFormContract 仅有明细入口、金额、币种、超标四类字段，未有项目负责人来源和按所有项目生成的会签。 [FinanceCatalog.java:80](../agentflow-domain/src/main/java/io/agentflow/finance/FinanceCatalog.java#L80)、[ExpenseFormContract.java:17](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseFormContract.java#L17) | 由可信项目目录解析每个实际分摊项目负责人并冻结；去重不丢项目责任；空匹配阻断；多项目必须全部完成且不能绕过字段权限。 原依据：05 §9。 |
| F15 | 费用财务专用报表 | 当前运营统计涵盖审批均时、退回、SLA、通知和 Agent 采纳；没有费用 P50/P90、超标核减、查验重复拦截、借款账龄和计划执行率的读模型。 [ApprovalOperationsReadPort.java:36](../agentflow-domain/src/main/java/io/agentflow/approval/operations/ApprovalOperationsReadPort.java#L36)、[operations-outcome-metrics.md:1](operations-outcome-metrics.md#L1) | 按法人/部门/类别提供提交至批准至付款 P50/P90、超标核减和退回原因、查验失败和重复拦截、借款账龄/计划执行率、凭证/付款失败积压；口径、未知样本与权限可核验。 原依据：05 §17。 |
| F16 | 出纳工作台法人/账户/到期日筛选 | CashierPaymentWorkspace.list 只接受 limit、beforeId，支付条款也没有付款到期日；选支付账户和批次逐笔检查已经存在。 [CashierPaymentWorkspace.java:81](../agentflow-server/src/main/java/io/agentflow/finance/CashierPaymentWorkspace.java#L81)、[payment-batches.md:1](payment-batches.md#L1) | 定义可信付款到期事实并接入筛选/排序；法人和实际支付账户筛选与总数、分页一致；保持逐笔复核及当前权限；不能把借款归还日当付款到期日。 原依据：05 §10.2；01 费用报销页面。 |
| F17 | 付款结果与财务异常业务通知 | 员工及供应商付款、原凭证通知已接通；本阶段完成费用预算原操作通知与受控详情，85 项 Java、77 项前端用例通过。仍缺独立冲销、业务结算及其他恢复来源、固定包和环境验收。见[预算通知说明](budget-notifications.md)与[阶段证据](evidence/budget-notifications-20261003.json)。 | 真实结果/冲突驱动最小通知；当前接收和读取权限、重复/迟到去重、原记录绑定及未知不冒充成功。原依据：04 §3.F。 |
| W01 | 白名单服务任务的设计与执行 | 公开目录、设计绑定、主子流程预检、两种设计视图及授权运行记录接口与页面已接通。固定包三轮强退、原号状态读取及数据库/合成接收方配套恢复通过；真实浏览器和 PostgreSQL 新范围补测待完成。 [公开设计证据](evidence/service-task-public-design-20261003.json)、[实现说明](service-tasks.md)。 | 仅可信已声明操作可设计、验证、模拟和发布；后台调用事务外执行，结果有界且幂等，未知可恢复；不允许任意 URL、Bean 或脚本。 原依据：04 §3.D；02 §7。 |

### 本地验收与核对

| 编号 | 待完成目标 | 当前事实 | 验收终点 |
| --- | --- | --- | --- |
| V01 | 票据模型路径及执行中重启的完整运行验收 | 已完成本地 XML 页面、终态重启及确认结果填报。证据明确未完成模型路径、排队/执行中重启的实际运行；范围测试不能替代。 [invoice-extraction-ui-20261002.json:1](evidence/invoice-extraction-ui-20261002.json#L1)、[expense-invoice-fill-20261002.json:1](evidence/expense-invoice-fill-20261002.json#L1) | 使用受控本地模型完成图片、PDF、XML 兜底及接通后的 OFD 实际 HTTP/页面；排队与执行中重启、超时、迟到结果、权限和来源变动、原键恢复通过；不算真实模型准确率。 原依据：05 §4.1、§16；04 §6。 |
| V02 | 最终版本整体验收与发布资料 | 现有 256 份分阶段证据，验证对象为各自提交。最新组件范围验证不是所有待开发功能完成后的整体验收，旧部署记录也不等于当前分支已发布。 [postgres-release-gate.md:1](postgres-release-gate.md#L1)、[production-database-lifecycle.md:1](production-database-lifecycle.md#L1) | 全部本地待办完成后，以固定提交和安装包运行适当完整 Java/Web/工具门禁、OpenAPI、非空升级/回退与原在审接续，更新运行文档和验收矩阵。 原依据：04 §6；02 §11、§12。 |
| V03 | 余下原始条款的代码与验收证据核对 | 本次已建立 00–05 来源摘要、307 操作库存和已确认缺口；尚未逐条关闭所有界面细节、键盘/触屏/无障碍、非功能约束及每条验收断言。 [product-goal-gap-audit.md:1](product-goal-gap-audit.md#L1)、[remaining-implementation-plan.md:1](remaining-implementation-plan.md#L1) | 对尚未证明的每条规范补到代码/准确测试/真实运行证据或已授权调整；发现新缺口追加稳定编号，不能把检索无命中或有类名当成完成证明。 原依据：00 §1–§6；01 §4–§10；03 §1–§8。 |

### 真实企业联调与交付

| 编号 | 待完成目标 | 当前事实 | 验收终点 |
| --- | --- | --- | --- |
| E01 | 真实企业邮件投递 | SMTP/TLS、发件身份、收件权限、超时和最终递送需在企业邮件系统验收；本地受控 SMTP 只证明协议。 [notification-delivery.md:1](notification-delivery.md#L1) | 真实账号完成发送、退信及未知恢复，核对最终递送与最小通知内容。 原依据：04 §3.F。 |
| E02 | 真实企业 IM 投递 | 企业微信已有参考发送器与回环验收；企业应用、账号映射及权限未完成真实验收。 [wecom-notifications.md:1](wecom-notifications.md#L1) | 实际企业应用和成员映射、可见范围、受理/最终可见、限流及错误恢复验证。 原依据：04 §3.F。 |
| E03 | 真实财务主数据与制度 | 法人、账户、汇率、目录和制度现有受控端口/夹具；企业权威数据、实际制度版本未接入验收。 [finance-gateway.md:1](finance-gateway.md#L1) | 核对法人、员工账户、币种、汇率日期与精度、目录和制度事实及失效行为；不把样例金额作为企业标准。 原依据：05 §15、§19。 |
| E04 | 真实发票查验 | 税务/发票服务当前没有企业凭据和实际回执对照。 [invoice-verification.md:1](invoice-verification.md#L1) | 购方名称税号、票据身份、金额、作废/红冲、时效、重验与重复占用对照真实回执。 原依据：05 §4。 |
| E05 | 真实预算冻结与占用对账 | 本地异步预算链路已验收，仍缺企业冻结/释放/消费及未知结果的真实对账。 [budget-operations.md:1](budget-operations.md#L1) | 分摊行粒度、重复/迟到结果、核减差额、退回重提、消费及原账对账；企业控制模式明确。 原依据：05 §8。 |
| E06 | 真实 ERP 期间、映射与凭证 | 已有期间、科目、过账、冲回等端口和本地证据，没有企业 ERP 实际账簿验收。 [voucher-operations.md:1](voucher-operations.md#L1) | 核对期间、科目映射版本、实际凭证号、过账/冲回、失败重试及审计账与 ERP 一致。 原依据：05 §10.1。 |
| E07 | 真实银行/资金付款与回执 | 已完成本地支付授权、出纳、回调和对账协议；没有真实资金系统及银行原回单验收。 [payment-system-port.md:1](payment-system-port.md#L1) | 在企业测试环境核对账户、金额、双人控制、验签、重复/迟到/退票、最终资金事实和归档回单。 原依据：05 §10.2、§11。 |
| E08 | 企业模型连接与真实样本质量 | 合成回环模型只证明协议；摘要、草稿和真实票据的准确率、证据可靠性及外发范围尚无企业验收。 [agent-execution.md:1](agent-execution.md#L1) | 在用户授权的数据集和实际模型端点评估质量、错误和拒答，记录模型/提示版本、费用时延及人工复核结果。 原依据：04 §5、§8。 |
| E09 | 企业组织源同步联调 | 本地同步能力尚待 I01，真实人事目录协议、身份映射和冲突处理还需企业源系统验收。 [local-organization.md:1](local-organization.md#L1) | 同步新增/调岗/离职/停用、重复批次、断点恢复，与源目录及旧审批依据逐项对照。 原依据：04 §3.A、§3.G。 |
| E10 | 真实电子签服务联调 | 本地签署业务尚待 I02，真实签署服务、授权身份、证书及回执需要企业环境。 [expense-archives.md:1](expense-archives.md#L1) | 签署授权、文件摘要、回执、验签结果和归档链完整对照服务方，失败与未知结果可恢复。 原依据：04 §3.G。 |
| E11 | 企业 IdP 和 SSO 验收 | 已有 OIDC 登录/会话/退出及回环 IdP 证据；企业发行者、声明和会话策略未验收。 [enterprise-oidc.md:1](enterprise-oidc.md#L1) | 核对租户隔离、角色声明、实际登录退出/撤销、超时、共享会话和身份停用，不用本地目录替代认证。 原依据：02 §10。 |
| E12 | 目标环境安装、升级和配套恢复 | 已有容器、数据库和原件配套备份的本地方案，目标主机/存储、升级和跨环境恢复未实际验收。 [production-backup-recovery.md:1](production-backup-recovery.md#L1) | 按固定版本安装和非空升级；数据库及文件一致恢复，原在审继续；记录目标 RPO/RTO 和回退结果。 原依据：04 §3.A、§6。 |
| E13 | 目标容量、SLO 与告警 | 已有容量基线和告警规则证据，不能替代企业目标负载和真实告警接收链路。 [production-monitoring.md:1](production-monitoring.md#L1) | 按目标并发、数据量、附件规模测吞吐/延迟/错误及资源上限，演练真实告警并明确容量与 SLO。 原依据：02 §10、§13。 |
| E14 | 留存、数据位置和电子档案制度验收 | 本地封存、原件保留与权限已实现；企业留存期限、数据位置和档案接入要求尚无目标环境验收，不能由文档年份推定合规。 [expense-archives.md:1](expense-archives.md#L1) | 落实已确认留存及数据位置策略并验证访问、恢复和不可覆盖控制；如需企业档案系统，用实际接入证据验收。 原依据：05 §11。 |
| E15 | 远端提交、开放 PR 与 CI | origin 已配置且可访问，远端已有 main 与集成分支，当前没有开放 PR。本地开发分支 codex/governance-identifier-integration 尚未推送，也未完成对应最终版本 CI 验收。E15 依赖 V02 最终版本验收，仍为 OPEN。 | 目标仓库可用后以个人身份推送、创建开放 PR、附 PR 链接并验证 CI；无远端不虚构交付。 原依据：04 §7。 |

## 已从本台账完成

| 编号 | 目标 | 实现与验收 |
| --- | --- | --- |
| F01 | 费用制度与费用类别的版本化管理 | 实现 `14cf591`、`e7e0ce9`、`6753285`、`440704e`；固定提交 `a8baeee` 完成管理、填报提示、正式预检/提交、发布竞争、撤回重提、历史轮次和事前申请类别绑定。重启及独立恢复保留 240 张表、5,509 行、9 个附件，恢复后可继续完成审批。见[实现说明](expense-configuration.md)与[完整本地证据](evidence/expense-configuration-complete-20261003.json)。 |
| F02 | 科目映射版本的管理配置 | 实现 `09d3ad3`、恢复刷新修复 `d3e2e66`；固定代码 `7fc7813` 完成报销、借款和付款三类凭证链路、原依据页面、缺项/错误回显/发布竞争、处理中退出及原查询恢复。配套恢复保留 245 张表、6,276 行和 9 个附件，22 条旧命令的输入与摘要保持。见[实现说明](account-mapping-configuration.md)与[完整本地证据](evidence/account-mapping-complete-20261003.json)。 |
| F03 | 按行程自动计算定额补贴 | 实现 `bc4620b`；按发布日额和自然日计算，页面只读，API 拒绝篡改。320 项 Java、1,109 项前端测试通过，实际接口、桌面/390 像素页面、换版撤回重提及重启验证原轮次保持；9 张旧单与 7 份新修订核对通过。见[实现说明](expense-allowances.md)和[完整本地证据](evidence/expense-allowances-complete-20261003.json)。 |
| F06 | 本人手工关闭已批准事前额度 | 实现 `414b5d7`；API、原因确认、权限、版本、并发、审计、原键恢复及非空运行/重启通过。原批准和原预留保持，见[额度关闭](expense-request-closure.md)与[验收证据](evidence/expense-request-closure-20261002.json)。 |
| F07 | 借款冲销 FIFO 建议 | 实现 `2ab9a38`；有效预检金额、跨页排序、冻结与保留预留、明确采纳及人工调整经 API/页面验收，V101 原账本保持及进程重启通过。见[借款建议](advance-offset-suggestion.md)与[验收证据](evidence/advance-offset-suggestion-20261002.json)。 |
| F08 | 逾期借款提醒与新借款控制配置 | 实现 `70faba5`；配置三态、预检/提交双重检查、还款恢复、时区与跨页、一次性提醒、事务回滚与并发通过，V102 非空升级及实际页面/重启通过。见[逾期借款控制](advance-overdue-controls.md)与[验收证据](evidence/advance-overdue-controls-20261003.json)。 |
| F10 | 退回/撤回保留预算的到期释放 | 实现 `e987dcf`；固定包 `43e95e5` 完成真实撤回、退回及子流程传播，原号恢复、并发重提边界、版本 1→2→3 的重新冻结与其他资源预留保持。246 份实际响应、45 份请求契约、12 张桌面/390 像素截图通过；配套恢复保留 247 张表、1,926 条记录和 2 份原件。到期使用明确标记的合成时间夹具，真实企业预算仍由 E05 验收。见[完整本地证据](evidence/expense-budget-retention-complete-20261003.json)。 |
| F13 | 报销、事前申请和借款模板包 | 实现 `abd5563`、`7667849`；固定包完成三类模板发布与十个业务样例，另验证免纸件模式。53 条业务断言、493 份实际响应及 124 份请求契约、桌面/390 像素页面和原件下载通过。配套恢复保留 245 张表、1,375 行、1 份原件，在审单继续完成审批/付款/核销，40 条原财务回执保持。见[使用说明](financial-template-examples.md)与[完整本地证据](evidence/financial-template-journey-complete-20261003.json)。 |

## 已有能力与授权调整

- **预算不足自动退回**：不是缺失；柔性例外另见 F09。 [ExpenseBudgetOutcomeHandler.java:50](../agentflow-server/src/main/java/io/agentflow/expense/ExpenseBudgetOutcomeHandler.java#L50)。
- **核减通知**：已有最小消息及详情入口，受授权最小外发规则约束；不强迫消息复制金额。付款结果通知另见 F17。 [ApprovalNotificationService.java:114](../agentflow-server/src/main/java/io/agentflow/notification/ApprovalNotificationService.java#L114)。
- **多级主管、字段选人、职责分离和期限代理**：已完成各自本地验收；费用自动上溯/相邻去重为不同规则。 [form-assignees.md:1](form-assignees.md#L1)。
- **字段帮助文案**：FormSchema.Field 已定义 helpText，不能因原清单无勾选判为未开发。 [FormSchema.java:181](../agentflow-domain/src/main/java/io/agentflow/form/FormSchema.java#L181)。
- **初始化、审批运营结果指标和风险筛选**：已有本地运行证据；不替代财务报表与跨单风险。 [tenant-initialization-wizard-20261001.json:1](evidence/tenant-initialization-wizard-20261001.json#L1)。
- **归档与历史保留**：本地封存和原件权限已实现；企业制度验收另见 E14。 [expense-archives.md:1](expense-archives.md#L1)。

- 不提供批量批准；已实现批量领取/释放不重复计为待办。
- 受限白名单条件满足 DMN/受限表达式的选择要求，不因没有 DMN 重开规则能力。
- 附件采用已确认的单机持久目录；S3 非本次缺失项；历史文件不自动物理删除。
- 停用版本恢复原版本后才可重提，不按旧设计静默切换最新版本。
- 发起任职固定到轮次，申请人只在草稿/退回/撤回编辑，管理员仍受敏感字段约束。
- 财务 D1–D6 与后续推荐已授权；外部权威事实源保留，真实企业数据和阈值不能编造。

## 本轮核对证据与局限

- 已固定六份原始规范的摘要和规范范围，并检查初次核对基线 OpenAPI 的 272 条路径、307 个操作；缺口以实际实现和入口证实，不能只凭文档复选框或搜索无命中判断。
- 初次台账审计以当时的 256 份阶段证据定位已验证功能，没有重跑历史测试。此后 F01、F02、F06、F07、F08 分别补充了范围测试与隔离运行证据，不能当成整个分支的整体验收。
- 原表为 39 组规范章节建立到任务和证据的索引，但尚不是逐条验收证明。界面键盘、触屏、无障碍、跨页状态等细则仍由 V03 接续；发现新缺口按新编号追加。
- origin 已配置。本阶段仅完成本地开发，没有创建 PR 或 CI；E15 依赖 V02，远端交付前须重新核验分支与权限。

## 接续顺序

按本地优先的授权，F01、F02、F03、F06、F07、F08、F10、F13 已完成，W01 运行状态接口、页面范围验证和独立配套恢复已完成，剩余浏览器与 PostgreSQL 验收受环境条件影响；继续推进其他独立待办；F04 的聚合时间窗口和有效单据口径、F05 的超容差规则仍等待业务答复。Agent、组织同步和电子签仍保留在总目标中。每关闭一项必须补具体提交和匹配的验收证据，更新 JSON 状态后重新汇总，不再依赖历史“工作包数”。
