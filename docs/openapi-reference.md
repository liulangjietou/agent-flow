# 开放 API 与集成验收

当前已实现 API 的唯一机器可读契约位于 `agentflow-server/src/main/resources/api/openapi.json`，采用 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)。它随服务打包，并由已认证的 `GET /api/v1/openapi.json` 返回。接口文档页直接读取此资源，提供搜索、分组、参数、模型、curl 示例和 JSON 下载；没有另建业务状态或修改 DDD 聚合。

科目配置新增 9 个管理操作，覆盖草稿、不可变发布、法人／币种范围的当前选择与历史。专用角色、三版本确认、固定 ERP 目标与严格 JSON 边界见[科目映射版本管理](account-mapping-configuration.md)。管理发布尚未接入自动凭证准备，不能据此宣称业务凭证已经采用平台映射。

契约覆盖认证、流程设计与模板、申请、审批与抄送轮次、协作、Agent 执行及复核、工作台、消息、日历、系统自检，以及已实现的费用草稿、财务目录和个人发票原件。操作与模型数量以当前机器可读契约及验证输出为准。未来新增端点时同步更新契约，Java 路由对照测试会阻止遗漏或虚构端点。

事件契约的 9 个后台接口支持管理员明确发布、精确版本启停和审计历史，以及流程管理员只读选择已发布版本。原始发布正文不会随新版或启停改变；写入回放返回原回执后需要重新读取当前状态。此阶段尚无可接收外部事件或推进等待节点的公开入口，具体边界见[事件契约](event-contracts.md)。

## 最小集成顺序

1. 演示环境调用 `/api/v1/auth/login`，正文 tenantId、username、password，返回 `{token,user}`。默认演示租户 demo、密码 demo；可用账号见登录页。企业模式使用已配置的 OIDC 入口，真实企业联调状态另见对应验收证据。演示令牌是不透明会话值，服务重启或注销后失效；不要按 JWT 解析。
2. 所有后续接口带 `Authorization: Bearer <token>`。租户、用户与权限取自认证上下文；`/auth/me` 返回 `{actor}`，不会再次返回 token。
3. 管理账号复制模板或创建流程草稿，校验、模拟后发布。发布请求的 expectedRevision 在查询参数，changeNote 在正文；其他草稿更新在正文传 expectedRevision。
4. 申请人创建草稿（HTTP 201），保留服务端 version，再提交（HTTP 200）。NUMBER 表单字段传精确十进制字符串；BOOLEAN 传 JSON 布尔值。表单与流程版本绑定，不使用另一个版本的字段。
5. 审批人通过 `/workspace/tasks` 分页查询摘要，按任务 ID 读取最新 allowedActions 和 version，再提交动作。委派待回交期间受托人只能 RESOLVE，回交后责任人作决定。申请详情可读不等于任务可办。
6. 通过申请详情、轮次、轨迹、审计和消息确认实际结果。旧消息不授权当前任务；原任务结束返回 404 时，仍可尝试按原申请权限读取结果。

示例 curl 显示 `$BASE_URL`、`$TOKEN`、`$REQUEST_KEY` 占位符，不读取浏览器当前令牌；花括号路径参数与版本值须替换。BASE_URL 为部署根地址，例如本地 `http://127.0.0.1:8180`，不再追加第二个 `/api/v1`。

## 幂等、并发与错误

业务写接口的 `Idempotency-Key` 必填，只接受 1—128 个 ASCII 字母、数字、点、下划线、冒号和连字符。每次新意图使用新键；结果未知的重试必须保留原方法、路径、查询字符串、原始正文和原键。即使 JSON 意义相同，改变空白或字段顺序也是不同请求。键还绑定原账号与角色；成功结果保留 24 小时，过期返回 409，不重新执行。

成功响应头 `Idempotency-Replayed` 表示是否回放。业务写入、审计、消息和成功响应共用事务；错误不会变成一个成功幂等记录。校验、模拟、比较、条件语言升级、字段权限预览等只读 POST，以及登录/注销，不要求该键。不能把所有 POST 统一包装为业务恢复请求。

常见错误：400 参数/键无效；401 登录失效；403 无角色或资源操作权；404 资源不存在或不可见；409 版本、状态或幂等冲突；422 领域规则或表单校验失败。按接口列出的响应处理，不用 HTTP 200 推断流程已经批准。

业务错误为 `{code,message,traceId,path,details?}`；表单错误在 `details.fieldErrors`，图错误在 `details.definitionErrors`。必填查询参数缺失及查询／路径类型转换错误已统一为 `INVALID_REQUEST`；契约的 RequestError 仍兼容 Spring 标准错误。网关也可能返回非 JSON/空正文，调用方应先按 HTTP 状态保留结果未知的写请求，再走原键恢复。

票据抽取使用 `/invoices/{id}/extraction-runs` 下的来源预览、排队、分页、详情和本人复核接口。模型方式必须明确确认外发并绑定原件及目的地，本地 XML 不依赖模型。成功回执恢复不重新读取原件，文件准备在幂等写事务外执行；确认不修改税务查验或财务占用，详见[票据抽取](invoice-extraction.md)。管理员审计可按 `InvoiceExtractionRun` 来源及三种明确动作检索，不包含票面正文。

## 部分报销调整入口

部分报销调整已提供 `/expense-reports/{id}/partial-adjustments` 下的创建、`original-queries`、`preparations` 和 `authorizations` 四个 POST。先按原号只读刷新原件，再从真实前次完成净额和完整未使用入款创建意图；两侧分别准备和明确授权。所有入口及幂等回放要求当前独立财务和原轮次完整字段权限，回执仅携带定位与版本。可为零的原件／历史版本也必须明确传值，不能通过缺省值假定没有原记录。

同一路径的 GET 已提供原件版本、逐行真实完成净额、完整回款占用、历史结果及本人最新准备。申请人等原轮次合法读者可以读取历史，当前独立财务才可读取自己的准备；财务资格和授权能力预览不能代替写入时的重新核对。读取保持 `no-store`，不刷新原件或有效期。恢复／裁决动作及页面已完成本地运行验收，验证边界详见[部分报销调整](partial-expense-adjustments.md)。

## 分页与兼容

待办、我发起、草稿、已办、消息默认 30、最多 100；轨迹与审计默认 50、最多 100。nextCursor 是不透明值，分页沿用同一账号与筛选条件，不自行构造。待办末页省略 nextCursor；历史页末页明确为 null。总数和续页不是冻结快照，任务流转后可刷新首页。

旧 `/tasks` 和 `/applications` 保留全量数组契约。个人列表使用有界 workspace 接口，详情按 ID 查询；文档页明确标注兼容入口。审批规则使用 properties.assigneeRule，不是 assignee。组织、附件、财务付款、Agent 及部分高级流程已陆续完成本地验收，当前能力及尚未完成范围以[剩余清单](remaining-local-work.md)和对应证据为准。外部 Webhook 的实际能力与配置边界见 Webhook 交付文档。

## 验证与维护

- 根目录 `mvn verify`：OpenApiContractTest 对照 Spring 实际路由、所有记录类型请求 DTO 和主要响应 DTO 字段，并验证匿名拒绝、登录可读与静态内容无会话数据。
- 前端 `npm run test:requests` 执行请求/模型测试，并调用 `npm run test:openapi`。后者使用固定版本 Swagger Parser 校验 OAS、Ajv 校验响应 schema、请求样例和历史页显式 null。用例及契约数量以当次输出为准。依赖仅在开发阶段使用，不进入浏览器包。
- 在独立演示库运行 `node scripts/check-openapi.mjs http://127.0.0.1:8082 --exercise`（工作目录 agentflow-web）。脚本限制 loopback，显式 --exercise 才写入；从契约读取样例、逐次验证原基础场景的真实响应，后续功能有各自运行证据，不宣称基础脚本遍历全部新增端点。创建随机前缀流程、模板副本和申请，执行批准、撤回、作废、已读、日历修订与试算；数据保留，不对正式库执行。
- 只读文档页不提供在线审批执行器。浏览器验收覆盖接口搜索、权限/幂等说明、模型展开、无结果清空、错误重试和窄屏；其结果与自动化测试分开记录。

结构校验不能证明业务权限和状态正确；实际调用验收也不能覆盖所有业务分支。授权、事务和表单边界仍由原领域与集成回归测试验证。普通已认证账号可读契约，读取文档不会改变其接口权限。

流程管理员可通过 `/process-definitions/assignee-options` 读取当前身份源中的可配置审批账号和角色。发布会重新核对有效成员，完整语义见[审批人配置](designer-assignees.md)。

人工节点 `properties.approvalMode` 支持 SINGLE（旧定义缺省）、ALL、ANY 和 PERCENT；PERCENT 必须显式提供 `approvalPercentage`，为 1 至 100 的整数字符串。任务详情返回 `countersign.total/completed/mode/required`，比例模式另含 `percentage`，门槛按实际激活名单向上取整并固定。达标结束的待办保留取消历史，不生成批准意见；`TASK_COUNTERSIGN_COMPLETED` 通知直接打开受权限检查的申请。新模式固定分母，仅 ALL 支持加减签，具体兼容与责任边界见[会签策略](countersign-policies.md)和[全员会签](all-countersign.md)。

全员会签的加减签使用独立入口：`GET /api/v1/tasks/{taskId}/countersign-members` 读取实际责任集合，`POST /api/v1/tasks/{taskId}/countersign-changes` 明确增加或移除未决责任。请求携带 `action=ADD/REMOVE`、唯一目标、原因、申请版本及原幂等键，不接受查询参数或客户端提供的轮次与人数。管理员不能越过实际任务关系；委派期间不能增减。响应为 `no-store`，结果未知按原请求恢复，成功不代表该节点已同意。申请审计、管理员审计及本人已办支持 `ADD_SIGNER`、`REMOVE_SIGNER`，取消通知为 `TASK_COUNTERSIGN_REMOVED`。完整边界见[加减签办理](countersign-membership.md)。

审批运营接口仅允许 ADMIN。轮次按 UTC 提交窗口统计，当前积压独立于日期窗口；退回率排除撤回。详见 [审批运营统计](approval-operations.md)。

首次流程进度通过 `/system/first-workflow` 查询当前租户精确发布版本的实际轮次，不从旧版本或本地步骤勾选推断完成。详见[首次流程使用引导](first-workflow-guide.md)。

跨端口浏览器调用时，`AGENTFLOW_WEB_ORIGIN` 必须与实际前端来源一致。CORS 过滤器在认证前核对来源和预检，实际请求仍需 Bearer 令牌；允许来源可读取结构化 401 响应。其他来源、未支持的方法和头仍被拒绝。

工作日历管理、历史与试算仅允许 ADMIN。写请求携带原修订及幂等键，试算明确指定 revision 且为只读 POST。详见[工作日历与期限试算](business-calendars.md)。

申请人可通过 `/applications/{id}/cancel` 作废草稿、已退回或已撤回的申请。Application 当前状态允许 CANCELLED；原 SubmissionRound 和已办当时状态保持原结论，不能统一改成作废。详见[申请作废](application-cancellation.md)。

管理员可用 `GET /api/v1/operations/applications` 检索租户申请摘要，按创建时间稳定分页；筛选和角色边界见 [管理员申请检索](application-search.md)。原 `/applications` 数组接口仍作为兼容入口，管理员 UI 已切换为分页查询。

## 字段权限预览

流程设计者可调用 `POST /api/v1/process-definitions/field-preview`，提交 `formSchema`、`values`、`nodeIds` 查看测试内容的字段投影。空节点集合表示没有节点身份的管理员，多个节点取更严格规则；结果为 `schema`、`payload`、`restricted`，隐藏字段同时移除配置与值，脱敏值固定为“已脱敏”。该入口只对调用方提供的内容计算，不接收申请 ID、不读取真实业务内容，要求 ADMIN 或 PROCESS_ADMIN，并返回 no-store。调用链和当前范围验收见[任职上下文与字段权限实施记录](organization-context-and-field-permissions.md)。

## 条件语言兼容

图的 `conditionLanguageVersion` 缺省为 1，显式 2 启用括号、取反及枚举 IN。升级入口 `/process-definitions/upgrade-conditions` 只转换并返回图，不保存或发布；调用方应保留返回的版本字段。完整语法、缺失值语义和版本迁移见 [条件语言说明](condition-language.md)。

校验接口新增 `branchDiagnostics`，返回遗漏/重叠的精确字符串示例。缺少覆盖且无默认分支阻止发布，重叠与无法证明覆盖为提醒；提醒随发布摘要保存。草稿保存仍允许覆盖待修正，详见 [分支覆盖检查](branch-coverage.md)。


供应商采购付款办理使用 `/api/v1/procurement-payments/{id}/supplier-payment` 读取原轮次状态，`/reviews` 登记新鲜应付复核，`/authorizations` 消费同一财务的证据并登记固定预留。`/api/v1/supplier-payments/{id}/finance-actions` 只恢复同一原授权；`QUERY`、`RETRY`、`RETIRE` 分别受原状态和展示版本约束。FINANCE 角色、非申请人、原轮次敏感字段可读与当前法人任职缺一不可，幂等回放也重新检查。202 不是银行付款或 ERP 结算成功，完整规则见 [供应商付款](supplier-payments.md)。

本人通知偏好使用 `GET/PUT /api/v1/notifications/preferences`：默认关闭外部开关，站内提醒常开，不接受其他人的身份或外部地址。PUT 显式携带两个开关与 `expectedVersion`，响应禁止缓存，原键恢复回放历史回执后应重新读取当前设置。保存成功不代表外部渠道接通或消息送达，见[个人通知偏好](notification-preferences.md)。
