# 当前未完成任务台账

更新：2026-10-02，F06 本地验收完成后剩余 41 项。初次核对基线 `f5fd8d53c3d6bbafef456d96c462320bd186d8fc`，分支 `codex/governance-identifier-integration`。

**当前已确认 41 项未完成：23 项本地开发、3 项本地验收与核对、15 项需要真实企业环境或远端的联调/交付。全项目至少剩余 41 项。** V03 尚未完成，所以不能声称这是全量精确总数，也不能给完成百分比。

此前“6 项”是六个工作类别，不能当成六个小任务。本次发现原费用方案里的管理入口、补贴、跨单风险、额度控制、项目审批和报表仍有缺口，已单独编号；这些来自既定方案，没有新增产品需求。

计数单位是一个可独立验收的交付目标。同一功能的后台、页面、迁移和测试合算一项；本地实现与企业实际联调分开。已完成 OFD 组件属于 A01 的阶段进展，不把每个图元或测试计成新任务。

机器可读原表：[remaining-task-ledger.json](remaining-task-ledger.json)。原始规范为相邻 `doc/00` 至 `doc/05`，路径、SHA-256、规范行范围保存在原表；后附历史进度不再重复计为新需求。

## 数量与范围

| 分类 | 未完成数 | 主要内容 |
| --- | ---: | --- |
| Agent | 4 | OFD、模型预检解释、结构化财务填报、费用风险建议 |
| 组织与电子签 | 2 | 外部组织同步、电子签业务用例 |
| 费用与财务产品 | 16 | 制度、补贴、额度、借款、路由、模板、报表、出纳及结果通知 |
| 流程能力 | 1 | 白名单服务任务 |
| 本地验收与核对 | 3 | 票据模型完整运行、最终版本验收、余下原条款核对 |
| 真实企业联调与交付 | 15 | 通知、财务、模型、组织、签署、IdP、部署恢复、容量、留存和 PR/CI |

## 逐项清单

### 本地开发

| 编号 | 待完成目标 | 当前事实 | 验收终点 |
| --- | --- | --- | --- |
| A01 | OFD 完整票据渲染与应用抽取入口 | 基础图元、模板、字体、静态批注、复合图元和隔离进程已有范围证据；公开抽取仍未支持 OFD。缺其余绘制能力、数字签章与嵌套内容、应用字体配置及全页模型输入。 [invoice-extraction.md:78](invoice-extraction.md#L78)、[InvoiceExtractionSources.java:1](../agentflow-server/src/main/java/io/agentflow/agent/InvoiceExtractionSources.java#L1) | 完整原件逐页对照；签章不能被静默丢弃；受控字体和资源限制经实际安装包验证；公开入口及完整页输入接通。签章图像呈现与签名有效性分别声明。 原依据：05 §4.1、§16；05 §16。 |
| A02 | 模型预检解释与补正建议 | 现有 ExpensePrecheckEvaluator 执行权威规则预检；现有 Agent 能力为摘要、普通草稿和票据建议，没有模型解释预检结果的专用用例。 [ExpensePrecheckEvaluator.java:1](../agentflow-server/src/main/java/io/agentflow/expense/ExpensePrecheckEvaluator.java#L1)、[draft-assist.md:3](draft-assist.md#L3) | 输入绑定当前预检及选定可读事实；解释带来源；过期结果不可采纳；建议不修改规则结论、金额或审批结果。 原依据：05 §16；04 §5。 |
| A03 | 结构化财务业务填报助手 | 普通草稿入口对 businessReference 非空明确返回 AGENT_DRAFT_UNSUPPORTED。票面金额辅助填报已完成，但没有按行程生成费用行、类别和分摊建议的专用入口。 [DraftAssistInputs.java:28](../agentflow-server/src/main/java/io/agentflow/agent/DraftAssistInputs.java#L28)、[expense-invoice-fill-20261002.json:1](evidence/expense-invoice-fill-20261002.json#L1) | 结构化业务建议走各自领域保存服务；报销行程、类别和分摊逐项人工确认；不由模型生成补贴标准、真实余额或批准事实；适用业务边界明确。 原依据：05 §16；04 §5。 |
| A04 | 费用异常的 Agent 风险提示 | 当前 ApprovalRiskPolicy 只评估本次表单白名单条件。没有费用领域同日多笔、节假日消费、连号等事实的专用模型提示与复核链路。 [ApprovalRiskPolicy.java:48](../agentflow-domain/src/main/java/io/agentflow/definition/ApprovalRiskPolicy.java#L48)、[agent-execution.md:1](agent-execution.md#L1) | 可解释地引用经授权的费用事实，保留提示和人工处置；不能凭模型标签自动驳回、核减或改变金额矩阵。确定性拆单路由由 F04 独立处理。 原依据：05 §16。 |
| I01 | 外部组织同步的本地完整用例 | 现有组织目录为可信管理员维护单位、人员、任职和关系；已实现 OIDC，但没有同步批次、来源映射、冲突处置及恢复入口。 [OrganizationController.java:1](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationController.java#L1)、[OrganizationService.java:1](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationService.java#L1) | 以单一可信源映射组织事实，预检和具名应用变更、停用及重试均有审计；不隐式创建认证身份或授予系统角色；旧审批轮次依据保持。 原依据：04 §3.A、§3.G；04 §3.G。 |
| I02 | 电子签业务端口、状态与操作页面 | 现有事件和支付 HMAC 验签解决消息完整性；未有文件签署业务用例。OFD 签章渲染也不等于电子签服务。 [product-goal-gap-audit.md:1](product-goal-gap-audit.md#L1)、[expense-archives.md:1](expense-archives.md#L1) | 固定文件与签署版本、显式授权、异步状态、可验证回执、重复回调和未知结果恢复；保留原件、授权及签署结果，完成本地受控协议验收。 原依据：04 §3.G；00 §6。 |
| F01 | 费用制度与费用类别的版本化管理 | 现有 GatewayExpensePolicies 查询外部判定，ExpensePolicySnapshot 冻结版本；类别来自 FinanceCatalog。当前 308 操作的 OpenAPI 中没有制度/类别管理与发布入口。 [GatewayExpensePolicies.java:1](../agentflow-server/src/main/java/io/agentflow/finance/GatewayExpensePolicies.java#L1)、[ExpensePolicySnapshot.java:1](../agentflow-domain/src/main/java/io/agentflow/expense/ExpensePolicySnapshot.java#L1) | 租户可管理类别和制度版本，覆盖法人、类别、城市等级、职级、日期、币种等条件；发布后不可覆盖；填报反馈与提交结论可追溯到同一权威源；不硬编码企业标准。 原依据：05 §5、§12.6；05 §12.6；04 §3.B、§6。 |
| F02 | 科目映射版本的管理配置 | GatewayAccounting 只查询映射并向 ERP 发送凭证；未有管理草稿、发布或模板样例配置的产品入口。 [GatewayAccounting.java:23](../agentflow-server/src/main/java/io/agentflow/finance/GatewayAccounting.java#L23)、[voucher-preparation.md:1](voucher-preparation.md#L1) | 提供类别与法人维度的版本管理或明确的权威系统管理接入；旧凭证保留原映射证据；无有效映射仍阻断；本地演示可配置样例并完成凭证链路。 原依据：05 §10.1；05 §12.6。 |
| F03 | 按行程自动计算定额补贴 | ExpenseLine 接收 quantity、日期和手填 claimedGross；DAY 单位不等于补贴规则，尚无系统计算的只读补贴行。 [ExpenseLine.java:17](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseLine.java#L17)、[ExpenseEditor.vue:1](../agentflow-web/src/components/ExpenseEditor.vue#L1) | 基于固定制度与行程计算天数和金额，页面显示依据且不能手改；服务端拒绝篡改；人工调整行程后重新计算并留存版本。 原依据：05 §5.1、§18；01 费用报销页面。 |
| F04 | 跨单拆单风险与合计金额路由 | 现有风险规则只读当前提交字段，没有同申请人、类别、时间窗口内跨单聚合及审批层级提升。 [ApprovalRiskPolicy.java:48](../agentflow-domain/src/main/java/io/agentflow/definition/ApprovalRiskPolicy.java#L48)、[ExpenseFormContract.java:1](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseFormContract.java#L1) | 窗口、阈值和启用状态显式配置；并发提交也按确定口径聚合；冻结风险及路由依据；不伪称未启用规则已经保护业务。 原依据：05 §5.3。 |
| F05 | 事前额度 STRICT/TOLERANCE/NONE 控制 | ExpenseRequest.ApprovedLine 能表达容差上限，但 ExpensePlan.approve 固定使用 BigDecimal.ZERO；没有按类别的模式、容差说明与额外审批配置。 [ExpensePlan.java:74](../agentflow-domain/src/main/java/io/agentflow/expense/ExpensePlan.java#L74)、[ExpenseRequest.java:134](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseRequest.java#L134) | 按已发布类别策略冻结控制模式和容差；超额说明与审批同步验证；并发占用、释放和关闭保持额度不变量。 原依据：05 §6.1。 |
| F07 | 借款冲销 FIFO 建议 | 现有本人借款列表按资源游标返回，ExpenseFundingPicker 逐笔选择后手填金额；未按放款日生成先进先出建议。 [ExpenseWorkspaceQuery.java:82](../agentflow-server/src/main/java/io/agentflow/expense/ExpenseWorkspaceQuery.java#L82)、[ExpenseFundingPicker.vue:21](../agentflow-web/src/components/ExpenseFundingPicker.vue#L21) | 跨分页按放款日期及稳定次序建议，在核定金额和可用余额内分配；申请人可调整，最终提交重验；预留和冻结借款不被错误使用。 原依据：05 §6.2；01 费用报销页面。 |
| F08 | 逾期借款提醒与新借款控制配置 | EmployeeAdvance.overdue 能判断逾期，服务端生产调用链没有使用；现有 SLA 催办针对审批任务，不能当作借款逾期提醒。 [EmployeeAdvance.java:80](../agentflow-domain/src/main/java/io/agentflow/expense/EmployeeAdvance.java#L80)、[AdvanceRequestCheckEvaluator.java:1](../agentflow-server/src/main/java/io/agentflow/expense/AdvanceRequestCheckEvaluator.java#L1) | 按法人日期和实际未还余额生成一次性/可追溯提醒；还款后停止；是否阻止新借款显式配置并由服务端执行，未配置不声称生效。 原依据：05 §6.2。 |
| F09 | 柔性预算超支审批 | ExpenseBudgetOutcomeHandler 对 BUDGET_INSUFFICIENT 已实现自动退回；不存在区分柔性预算及预算负责人审批的路径，刚性不足不是缺失项。 [ExpenseBudgetOutcomeHandler.java:50](../agentflow-server/src/main/java/io/agentflow/expense/ExpenseBudgetOutcomeHandler.java#L50)、[ExpensePrecheckEvaluator.java:114](../agentflow-server/src/main/java/io/agentflow/expense/ExpensePrecheckEvaluator.java#L114) | 外部预算明确允许柔性策略才进入指定审批；例外不得伪造冻结成功；审批后重新确认预算，未知和拒绝状态保持阻断。 原依据：05 §8.1、§9；05 §9。 |
| F10 | 退回/撤回保留预算的到期释放 | ExpenseReleaseService 明确保留退回和撤回占用，只处理驳回和作废释放；未有按已配置 N 天释放的调度。 [ExpenseReleaseService.java:35](../agentflow-server/src/main/java/io/agentflow/expense/ExpenseReleaseService.java#L35)、[expense-submission.md:1](expense-submission.md#L1) | 显式保留期、可追溯释放队列；重提与过期释放并发不误释放新轮次；未知外部结果对账，重新提交重新预检。 原依据：05 §8.2。 |
| F11 | 费用自审批场景自动上溯 | 通用职责分离已过滤申请人并在无人时阻断；多级主管可显式选择，但遇到费用自审批时不会自动沿任职关系上溯并审计。 [FlowableApprovalResponsibilities.java:85](../agentflow-server/src/main/java/io/agentflow/approval/process/FlowableApprovalResponsibilities.java#L85)、[OrganizationAssigneeResolver.java:107](../agentflow-server/src/main/java/io/agentflow/organization/OrganizationAssigneeResolver.java#L107) | 仅已发布费用策略启用；固定任职依据、检测环路与空上级，记录原候选和替代人及规则版本；财务职责分离仍强制。 原依据：05 §9.1。 |
| F12 | 相邻业务审批人重复的受控自动通过 | 现有职责分离记录真实批准人以排除冲突，不实现相邻相同审批人自动通过；未有该类审计动作。 [FlowableApprovalResponsibilities.java:1](../agentflow-server/src/main/java/io/agentflow/approval/process/FlowableApprovalResponsibilities.java#L1)、[approval-responsibilities.md:1](approval-responsibilities.md#L1) | 按发布版本识别相邻业务节点，记录自动动作和来源；非相邻默认不跳过；财务签收、审核、复核永不自动跳过；并发和重启不重复推进。 原依据：05 §9.1。 |
| F13 | 报销、事前申请和借款模板包 | 运行入口已有三类专用业务，但 ClasspathProcessTemplateCatalog 仅有请假、用印、合同、采购付款、预算调整 5 个模板；没有这 3 个可复制模板及配套样例。 [ClasspathProcessTemplateCatalog.java:21](../agentflow-server/src/main/java/io/agentflow/template/ClasspathProcessTemplateCatalog.java#L21)、[process-templates.md:1](process-templates.md#L1) | 3 个模板可复制、验证、模拟、发布并从专用入口提交；附类别、制度、映射、签收开关和超标/核减/冲销/重复票样例；金额矩阵及财务复核阈值可配置。 原依据：04 §3.B、§6；05 §9。 |
| F14 | 项目分摊驱动的项目负责人会签 | FinanceCatalog.Project 只有法人、代码、名称；ExpenseFormContract 仅有明细入口、金额、币种、超标四类字段，未有项目负责人来源和按所有项目生成的会签。 [FinanceCatalog.java:80](../agentflow-domain/src/main/java/io/agentflow/finance/FinanceCatalog.java#L80)、[ExpenseFormContract.java:17](../agentflow-domain/src/main/java/io/agentflow/expense/ExpenseFormContract.java#L17) | 由可信项目目录解析每个实际分摊项目负责人并冻结；去重不丢项目责任；空匹配阻断；多项目必须全部完成且不能绕过字段权限。 原依据：05 §9。 |
| F15 | 费用财务专用报表 | 当前运营统计涵盖审批均时、退回、SLA、通知和 Agent 采纳；没有费用 P50/P90、超标核减、查验重复拦截、借款账龄和计划执行率的读模型。 [ApprovalOperationsReadPort.java:36](../agentflow-domain/src/main/java/io/agentflow/approval/operations/ApprovalOperationsReadPort.java#L36)、[operations-outcome-metrics.md:1](operations-outcome-metrics.md#L1) | 按法人/部门/类别提供提交至批准至付款 P50/P90、超标核减和退回原因、查验失败和重复拦截、借款账龄/计划执行率、凭证/付款失败积压；口径、未知样本与权限可核验。 原依据：05 §17。 |
| F16 | 出纳工作台法人/账户/到期日筛选 | CashierPaymentWorkspace.list 只接受 limit、beforeId，支付条款也没有付款到期日；选支付账户和批次逐笔检查已经存在。 [CashierPaymentWorkspace.java:81](../agentflow-server/src/main/java/io/agentflow/finance/CashierPaymentWorkspace.java#L81)、[payment-batches.md:1](payment-batches.md#L1) | 定义可信付款到期事实并接入筛选/排序；法人和实际支付账户筛选与总数、分页一致；保持逐笔复核及当前权限；不能把借款归还日当付款到期日。 原依据：05 §10.2；01 费用报销页面。 |
| F17 | 付款结果与财务异常业务通知 | InboxMessage.Kind 已有审批和核减事件，但没有付款结果或异常事件；PaymentOperationChanged 现有监听器用于凭证和结算，不向收件人生成消息。 [InboxMessage.java:31](../agentflow-domain/src/main/java/io/agentflow/notification/InboxMessage.java#L31)、[PaymentOperationService.java:127](../agentflow-server/src/main/java/io/agentflow/finance/PaymentOperationService.java#L127) | 真实结果/冲突驱动最小站内通知与外发意向；接收人和读取权限实时校验；重复或迟到事件去重；未知不写成成功，核减既有通知保持。 原依据：04 §3.F。 |
| W01 | 白名单服务任务的设计与执行 | NodeType 含 SERVICE_TASK，但 DefinitionValidator 明确拒绝，发布适配器没有该分支。抄送使用内部 serviceTask 不代表对用户开放服务任务。 [DefinitionValidator.java:90](../agentflow-domain/src/main/java/io/agentflow/definition/DefinitionValidator.java#L90)、[FlowableDefinitionDeploymentAdapter.java:109](../agentflow-server/src/main/java/io/agentflow/definition/FlowableDefinitionDeploymentAdapter.java#L109) | 仅可信已声明操作可设计、验证、模拟和发布；后台调用事务外执行，结果有界且幂等，未知可恢复；不允许任意 URL、Bean 或脚本。 原依据：04 §3.D；02 §7。 |

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
| E15 | 远端提交、开放 PR 与 CI | 当前 git remote 为空；本地提交存在，没有 GitHub PR 或远程 CI 结果。 [remaining-implementation-plan.md:1](remaining-implementation-plan.md#L1) | 目标仓库可用后以个人身份推送、创建开放 PR、附 PR 链接并验证 CI；无远端不虚构交付。 原依据：04 §7。 |

## 已从本台账完成

| 编号 | 目标 | 实现与验收 |
| --- | --- | --- |
| F06 | 本人手工关闭已批准事前额度 | 实现 `414b5d7`；API、原因确认、权限、版本、并发、审计、原键恢复及非空运行/重启通过。原批准和原预留保持，见[额度关闭](expense-request-closure.md)与[验收证据](evidence/expense-request-closure-20261002.json)。 |

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
- 现有 256 份阶段证据用于定位过去已经验证的功能。初次台账审计没有重跑历史测试。此后 F06 单独补充了范围测试与隔离运行证据，不能当成整个分支的整体验收。
- 原表为 39 组规范章节建立到任务和证据的索引，但尚不是逐条验收证明。界面键盘、触屏、无障碍、跨页状态等细则仍由 V03 接续；发现新缺口按新编号追加。
- 无 Git remote，E15 仍未完成。本次只记录本地开发现状，不虚构 PR 或 CI。

## 接续顺序

按本地优先的授权，F06 已完成，接续 F07/F08 借款建议与逾期控制；费用制度和映射配置支撑 F13 模板包。Agent、组织同步和电子签仍保留在总目标中。每关闭一项必须补具体提交和匹配的验收证据，更新 JSON 状态后重新汇总，不再依赖历史“工作包数”。
