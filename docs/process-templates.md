# 流程模板中心

2026-10-04 代码目录核对：当前提供请假、用印、合同审批三个通用表单模板，以及采购付款、预算调整、费用报销、事前申请和借款五个结构化模板，共 **8 个模板、43 个场景**。每个模板包含可编辑的流程图、版本化表单、审批策略说明、示例输入和期望结果；复制后进入当前租户的私有流程草稿，再使用现有设计器校验、模拟和发布。通用模板为 `businessType=FORM`，五类财务模板发布后须从对应业务入口填报和预检，普通申请接口不能绕过财务依据。预算模板详见[预算调整](budget-adjustments.md)。F13 原 v1 三份员工财务流程与 10 个业务样例已完成完整本地验收，见[原版本证据](evidence/financial-template-journey-complete-20261003.json)；报销 v2 上溯的新增验收见[运行记录](expense-self-approval-runtime.md)。

本阶段已完成 77 条范围测试、实际 HTTP 复制/模拟/发布以及三类模板的页面复制和 390 像素布局检查，见[2026-10-03 阶段证据](evidence/financial-templates-catalog-20261003.json)。这些结果不代表配套财务业务样例或 F13 整体验收完成。

## 模板内容与边界

| 模板 key | 名称 | 额外复核条件 | 默认路径 |
|---|---|---|---|
| `leave-request` | 请假申请 | `durationDays > 3` | 经理审批后结束 |
| `seal-application` | 用印申请 | `urgent == true` | 经理审批后结束 |
| `contract-review` | 合同审批 | `contractAmount >= 100000` | 经理审批后结束 |
| `procurement-payment` | 已验收采购付款 | 每笔均需人工复核 | 本次任职直属主管 → 财务复核 |

三个通用表单模板使用相同的受限图结构，节点标识为 `start`、`manager`、`route`、`review`、`end`。短路径为 `start → manager → route → end`；条件满足时为 `start → manager → route → review → end`。网关有显式默认分支，条件分支按图中的顺序保存。各节点的 `properties.x` / `properties.y` 为画布坐标字符串，不影响路由。

`manager` 配置 `role:MANAGER`，`review` 配置 `role:ADMIN`。这些是演示角色，不自动映射为直属上级、部门负责人或法务。`ADMIN` 在这里仅演示额外复核；模板复制后应按本租户制度修改审批人和阈值。复制后的草稿可配置[本地组织及动态任职规则](organization-context-and-field-permissions.md)。原模板保留演示规则，复制后的静态规则在发布时检查有效成员，动态规则在实际节点激活时检查；现有[职责分离](approval-responsibilities.md)和[期限代理](approval-proxies.md)按显式规则使用，不为旧模板自动补授权。

通知文案保存在 `notificationTexts`，事件名为 `SUBMITTED`、`RETURNED`、`APPROVED`。三个通用表单模板的版本 2 将 `notificationsAvailable` 设为 `true`：复制时转为流程定义的申请人站内文案，可在设计器修改后发布。文案随申请创建冻结，不替换申请字段。现有[邮件发送](notification-delivery.md)和[企业微信发送](wecom-notifications.md)已有本地协议验收，真实企业投递另行验收。详见[版本通知文案](definition-notification-texts.md)。

## 采购模板的独立入口

`procurement-payment` 为版本 1、财务分类。`supervisor` 使用 `role:ORG_SUPERVISOR_1`；`finance` 的 `role:FINANCE` 为占位配置，复制后必须绑定本租户具有审批资格的实际人员或岗位，空角色会被发布校验拒绝。两个节点均只读完整的采购敏感组。三个场景验证正额、最小正额及零额拒绝，不能代替实际原应付预检。

当前目录合计 8 个模板、43 个场景；新增费用报销 10 个、事前申请 6 个、借款 6 个。`check-process-templates.py` 的真实通用表单审批仅覆盖前三个 FORM 模板；五类结构化模板由各自专用业务的工作流测试及运行证据验证，不以目录场景数量证明实际财务链路通过。

## 员工费用、事前申请与借款模板

| 模板 key | 业务类型 | 敏感明细组 | 审批与后续业务 |
| --- | --- | --- | --- |
| `expense-report` | `EXPENSE` | `expenseDetails` | 主管、金额分档、制度例外、单据检查与签收、财务审核、条件复核；批准后独立办理凭证与付款 |
| `expense-plan` | `EXPENSE_PLAN` | `expensePlanDetails` | 主管、金额分档、财务复核；批准后形成可核销的计划额度 |
| `advance-request` | `ADVANCE_REQUEST` | `advanceRequestDetails` | 主管、金额分档、财务复核；批准挂账后另行授权放款，实际到账后形成借款余额 |

费用报销模板当前为版本 2，事前申请和借款模板仍为版本 1。报销 v2 在开始节点启用本次任职自审批上溯：正式提交固定候选，业务候选包含申请人时替换为直属主管，财务职责保持人工办理与人员分离；旧副本不会自动升级。金额矩阵采用本币示例：不超过 5,000 元只经过直属主管；超过 5,000 元增加部门负责人；超过 50,000 元再增加分管负责人。报销财务审核后，最新核定总额超过 10,000 元进入财务复核。这些值位于设计器可编辑的条件连线上；修改副本不会更新目录资源或其他租户的版本。

直属主管使用本次任职的 `ORG_SUPERVISOR_1`，随提交轮次固定。其余业务和财务角色为占位配置，复制后须在设计器绑定有效人员或岗位。签收、审核、复核人员还需财务角色及对应法人任职，并与申请人、本单业务审批人分离。原件要求由法人目录的 `paperReceiptRequired` 决定：启用时签收节点须登记原件，关闭时仍保留人工单据检查，不能把模板模拟当作收单事实。

报销的 `amount` 和 `overPolicy` 均由服务端冻结事实派生。核减后复核读取更新的核定金额，原制度要求的例外审批标识不被删除。零金额样例只说明核减后的路由值合法，不代表可以直接提交零申报。事前申请及借款的派生总额须大于零。

所有人工节点默认原样读取各自敏感明细组；管理员不获得字段旁路。模板加载复用三类业务的字段与审核契约，报销目录还要求纸件签收和财务审核为必经控制。场景模拟使用带真实表单的模拟入口，避免旧路径接口丢失费用职责校验依据。

项目分摊会签、柔性预算超支和相邻同人自动通过仍由对应任务实现。自审批上溯的 v2 模板已通过固定包升级、实际办理、重启和独立恢复，浏览器与 PostgreSQL 补验待完成，见[上溯验收](expense-self-approval-runtime.md)。配套类别、制度、映射、签收开关和 10 个业务输入见[员工财务样例包](financial-template-examples.md)。三类原 v1 模板已从专用入口完成十个样例及免纸件变体的本地办理、页面和配套恢复验收，见[原版本完整证据](evidence/financial-template-journey-complete-20261003.json)。

## 字段

三个通用模板采用 `schemaVersion=1` 格式；五类结构化财务模板使用 `schemaVersion=2` 的敏感明细与节点权限。标题、业务单号、流程版本由申请本身管理，不重复放入业务字段。数字使用十进制字符串，日期使用 `YYYY-MM-DD`，布尔值使用 JSON `true` / `false`；`false` 是已填写的有效值。具体类型和校验规则见 [版本化申请表单](./versioned-forms.md)。

### 请假申请

| 字段 | 类型 | 约束与含义 |
|---|---|---|
| `leaveType` | `SELECT` | 必填；`ANNUAL` 年假、`PERSONAL` 事假、`SICK` 病假 |
| `startDate` | `DATE` | 必填；有效开始日期 |
| `durationDays` | `NUMBER` | 必填；范围 `0.5` 至 `365`，大于 `3` 天增加复核 |
| `reason` | `TEXTAREA` | 必填；最多 1000 字符 |

请假天数由申请人填写，不计算工作日、节假日或假期余额，也不限制半天步进。当前没有结束日期与开始日期之间的跨字段校验。`3` 天是示例审批阈值，不代表企业制度。

### 用印申请

| 字段 | 类型 | 约束与含义 |
|---|---|---|
| `documentTitle` | `TEXT` | 必填；最多 200 字符 |
| `sealType` | `SELECT` | 必填；`COMPANY` 公章、`CONTRACT` 合同章、`FINANCE` 财务章 |
| `urgent` | `BOOLEAN` | 必填；急件增加复核，仍须先经经理审批 |
| `purpose` | `TEXTAREA` | 必填；最多 1000 字符 |
| `documentRef` | `TEXT` | 可选；最多 100 字符，仅作外部文件编号 |

选择印章类型不授予印章操作权限。模板不管理印章库存、领取归还、实体用印核销、附件、电子签章或归档关联。

### 合同审批

| 字段 | 类型 | 约束与含义 |
|---|---|---|
| `contractTitle` | `TEXT` | 必填；最多 200 字符 |
| `counterparty` | `TEXT` | 必填；最多 200 字符，不查询供应商或客户主数据 |
| `contractAmount` | `NUMBER` | 必填；不小于 `0`，单位为人民币元，达到 `100000` 元增加复核 |
| `contractType` | `SELECT` | 必填；`SALES` 销售、`PURCHASE` 采购、`SERVICE` 服务 |
| `summary` | `TEXTAREA` | 必填；最多 2000 字符 |

金额仅用于合同审批路由，不支持汇率换算或财务金额分位约束。零金额被示例表单允许，是否允许应由租户制度决定。审批通过不代表签约、法务审查、合同归档、预算占用、付款授权、发票查验或会计记账完成。

## 资源与版本

官方资源位于 `agentflow-server/src/main/resources/process-templates/`，文件名与模板 key 相同。三份通用表单资源的 `templateVersion` 均为 `2`，`category` 为 `OA`。模板版本、表单格式版本和租户流程发布版本是不同概念：复制模板 v2 会创建租户流程草稿，发布后由流程定义服务分配该租户流程的业务版本。原 v1 副本保持原配置，不自动启用文案。

资源只使用以下顶层属性：

| 属性 | 作用 |
|---|---|
| `key`、`templateVersion`、`name`、`category`、`description` | 模板标识、版本与目录展示 |
| `scope`、`businessType`、`dependencies` | 适用范围、业务类型、依赖能力与前置条件 |
| `defaultRoles`、`fieldDescriptions` | 示例角色代码和按字段 key 索引的说明 |
| `risks`、`upgradePolicy` | 未实现能力、示例边界和升级方式 |
| `notificationTexts`、`notificationsAvailable` | 三类申请人站内文案及复制时是否启用 |
| `graph`、`formSchema` | 复制给流程定义的真实图和表单 |
| `scenarios` | 合法输入、路由边界与字段错误的可执行预期 |

模板复制生成新的草稿 ID，图和表单成为租户草稿自己的内容，并保留来源模板 key/version 供追溯。复制不会直接部署引擎、启动审批或复制 Flowable 内部表。模板升级不自动覆盖已复制草稿、已发布版本或在途申请；需要新版本内容时重新复制，再由管理员审核修改和发布。

## 接口与使用步骤

本阶段沿用设计文档约定的接口：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/process-templates` | 返回目录数组，每项包含完整模板内容及当前租户的 `copies` |
| `GET /api/v1/process-templates/{templateKey}/scenarios` | 直接返回该模板的 `Scenario[]`，不额外包裹模板对象 |
| `POST /api/v1/process-templates/{templateKey}/copy` | 复制指定模板版本为当前租户的私有草稿 |

上述接口及财务配套样例读取接口都需要流程管理权限（`PROCESS_ADMIN` 或 `ADMIN`）。租户从认证上下文取得，不能在请求中指定其他租户。写请求携带 `Idempotency-Key`，响应丢失时使用原幂等键与原请求确认结果；不要用新键盲目再复制一次。

目录中的 `copies` 仅包含当前租户的副本，字段为 `definitionId`、`processKey`、`name`、`status`、`version`、`revision`、`templateVersion`、`copiedBy`、`copiedAt`。来源模板版本、复制人和复制时间用于追溯；名称、状态、流程版本和草稿 revision 反映副本当前状态。

复制请求示例：

```json
{
  "key": "team-leave",
  "name": "团队请假审批",
  "templateVersion": 2
}
```

请求中的 `key` 是要创建的租户流程 key，路径中的 `templateKey` 是来源模板 key。复制成功返回 HTTP 200 和现有 `DefinitionResponse`，可使用流程定义接口继续读取、编辑、模拟和发布。草稿状态为 `DRAFT`，初始 `version=0`、`revision=0`；复制本身不会改变现有已发布版本。

复制请求仅接受 `key`、`name`、`templateVersion`，非法请求返回 `400 INVALID_TEMPLATE_COPY_REQUEST`。未知模板返回 `404 NOT_FOUND`；已知模板的请求版本与目录当前版本不一致时返回 `409 TEMPLATE_VERSION_CONFLICT`，不静默改用最新版本。版本冲突后应重新查看模板内容，再发起新的复制操作。

使用顺序：

1. 在模板中心查看用途、字段、依赖、示例审批角色和限制，再选择模板版本。
2. 输入租户流程 key 与名称，复制为私有草稿。
3. 在设计器核对审批人、阈值和字段；使用模板的合法样例模拟短路径与长路径。
4. 校验通过后发布流程，再由员工创建申请、填写表单、提交和审批。
5. 修改表单或流程时创建下一版本草稿；既有申请继续使用已绑定的发布版本和轮次快照。

模板场景仅为脱敏样例。查看场景与模拟不创建业务申请，也不代表组织解析或通知已经执行。

## 场景预期与验收边界

每个场景包含 `id`、`name`、`description`、`payload`、`expectedPath` 和 `expectedFieldErrors`。合法场景的 `expectedFieldErrors` 为 `{}`，字段校验通过后使用同一领域模拟器比较完整节点路径。非法场景的 `expectedPath` 为 `[]`，比较全部预期字段错误，不把字段非法输入交给路由模拟。

| 模板 | 场景数 | 覆盖的预期 |
|---|---|---|
| 请假 | 6 | `1.5` 短路径、`3` 阈值默认路径、`4` 长路径、`0.5` 下界；非法类型选项、无效日期、低于下界、空白必填；`366` 超上界 |
| 用印 | 4 | `false` 默认路径、`true` 长路径；空标题、非法印章选项、字符串 `"false"`；缺少必填布尔值 |
| 合同 | 5 | `99999.99` 短路径、`100000` 和 `100000.01` 长路径、`0` 下界；负金额、非法合同类型 |

字段错误沿用现有领域码：`REQUIRED`、`INVALID_TYPE`、`INVALID_DATE`、`INVALID_OPTION`、`BELOW_MINIMUM`、`ABOVE_MAXIMUM`。资源中的每条负例都给出完整错误映射；合法数值全部使用十进制字符串。

本阶段验收应另行验证：复制后编辑不改变官方资源或其他副本；跨租户不能读取、改动对方草稿；相同幂等键重放只产生一个草稿；模板版本不被静默切换；复制后可发布并完成真实申请审批；后续模板更新不改变已发布定义与旧实例。这些要求不能用资源静态检查代替。

模板目录管理、模板升级差异合并、代理及附件尚不属于模板中心交付。动态组织与节点字段权限已在后续[任职上下文与字段权限](organization-context-and-field-permissions.md)中实现，复制后的草稿可配置，原模板不自动改写。站内文案、开始使用引导和重复明细的后续能力分别见相应功能文档；邮件和 IM 尚未接入。费用报销、采购付款与预算调整按各自结构化领域实现。已验收采购付款的模板、独立页面和本地审批验收见[采购付款](procurement-payments.md)。

## 模板文件复用

除复制内置目录外，管理员还可从模板中心进入[模板文件导入与导出](portable-process-templates.md)，把流程图和表单导入为租户独立草稿。文件不进入内置目录，也不携带目录样例、复制来源或发布历史。
