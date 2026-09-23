# 开放 API 与集成验收

当前已实现 API 的唯一机器可读契约位于 `agentflow-server/src/main/resources/api/openapi.json`，采用 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)。它随服务打包，并由已认证的 `GET /api/v1/openapi.json` 返回。接口文档页直接读取此资源，提供搜索、分组、参数、模型、curl 示例和 JSON 下载；没有另建业务状态或修改 DDD 聚合。

契约包含 37 个操作，覆盖认证、定义草稿/发布/校验/模拟/比较、模板、申请、轮次/轨迹/审计、任务、工作台、站内消息和系统自检。未来新增端点时同步更新契约，Java 路由对照测试会阻止遗漏或虚构端点。

## 最小集成顺序

1. 演示环境调用 `/api/v1/auth/login`，正文 tenantId、username、password，返回 `{token,user}`。默认演示租户 demo、密码 demo；可用账号见登录页。企业 IdP/OIDC 和服务账号认证尚未接入。令牌是不透明会话值，服务重启或注销后失效；不要按 JWT 解析。
2. 所有后续接口带 `Authorization: Bearer <token>`。租户、用户与权限取自认证上下文；`/auth/me` 返回 `{actor}`，不会再次返回 token。
3. 管理账号复制模板或创建流程草稿，校验、模拟后发布。发布请求的 expectedRevision 在查询参数，changeNote 在正文；其他草稿更新在正文传 expectedRevision。
4. 申请人创建草稿（HTTP 201），保留服务端 version，再提交（HTTP 200）。NUMBER 表单字段传精确十进制字符串；BOOLEAN 传 JSON 布尔值。表单与流程版本绑定，不使用另一个版本的字段。
5. 审批人通过 `/workspace/tasks` 分页查询摘要，按任务 ID 读取最新 allowedActions 和 version，再提交动作。委派待回交期间受托人只能 RESOLVE，回交后责任人作决定。申请详情可读不等于任务可办。
6. 通过申请详情、轮次、轨迹、审计和消息确认实际结果。旧消息不授权当前任务；原任务结束返回 404 时，仍可尝试按原申请权限读取结果。

示例 curl 显示 `$BASE_URL`、`$TOKEN`、`$REQUEST_KEY` 占位符，不读取浏览器当前令牌；花括号路径参数与版本值须替换。BASE_URL 为部署根地址，例如本地 `http://127.0.0.1:8180`，不再追加第二个 `/api/v1`。

## 幂等、并发与错误

业务写接口的 `Idempotency-Key` 必填，只接受 1—128 个 ASCII 字母、数字、点、下划线、冒号和连字符。每次新意图使用新键；结果未知的重试必须保留原方法、路径、查询字符串、原始正文和原键。即使 JSON 意义相同，改变空白或字段顺序也是不同请求。键还绑定原账号与角色；成功结果保留 24 小时，过期返回 409，不重新执行。

成功响应头 `Idempotency-Replayed` 表示是否回放。业务写入、审计、消息和成功响应共用事务；错误不会变成一个成功幂等记录。校验、模拟、比较等只读 POST，以及登录/注销，不要求该键。不能把所有 POST 统一包装为业务恢复请求。

常见错误：400 参数/键无效；401 登录失效；403 无角色或资源操作权；404 资源不存在或不可见；409 版本、状态或幂等冲突；422 领域规则或表单校验失败。按接口列出的响应处理，不用 HTTP 200 推断流程已经批准。

业务错误为 `{code,message,traceId,path,details?}`；表单错误在 `details.fieldErrors`，图错误在 `details.definitionErrors`。进入控制器前的 UUID 转换等失败，当前仍是 Spring `{timestamp,status,error,path}` 响应，契约使用 RequestError 联合类型表达。本文没有把所有既有异常重写为统一模型。网关也可能返回非 JSON/空正文，调用方应先按 HTTP 状态保留结果未知的写请求，再走原键恢复。

## 分页与兼容

待办、我发起、草稿、已办、消息默认 30、最多 100；轨迹与审计默认 50、最多 100。nextCursor 是不透明值，分页沿用同一账号与筛选条件，不自行构造。待办末页省略 nextCursor；历史页末页明确为 null。总数和续页不是冻结快照，任务流转后可刷新首页。

旧 `/tasks` 和 `/applications` 保留全量数组契约。个人列表使用有界 workspace 接口，详情按 ID 查询；文档页明确标注兼容入口。受限流程节点仅开始、人工审批、独占条件网关、结束，审批规则使用 properties.assigneeRule，不是 assignee。高级节点、停用/回滚、组织、附件、财务付款、Agent 和外部 Webhook 仍未交付。

## 验证与维护

- 根目录 `mvn verify`：OpenApiContractTest 对照 Spring 实际路由、所有记录类型请求 DTO 和主要响应 DTO 字段，并验证匿名拒绝、登录可读与静态内容无会话数据。
- 前端 `npm run test:requests`：101 项请求/模型测试，并调用 `npm run test:openapi`。后者使用固定版本 Swagger Parser 校验 OAS、Ajv 校验所有响应 schema 与 14 个请求样例，以及历史页显式 null。依赖仅在开发阶段使用，不进入浏览器包。
- 在独立演示库运行 `node scripts/check-openapi.mjs http://127.0.0.1:8082 --exercise`（工作目录 agentflow-web）。脚本限制 loopback，显式 --exercise 才写入；从契约读取样例、逐次验证真实响应，覆盖全部 37 个操作与 200/201/400/401/403/404/409/422。创建随机前缀流程、模板副本和申请，执行批准、撤回、已读；数据保留，不对正式库执行。
- 只读文档页不提供在线审批执行器。浏览器验收覆盖接口搜索、权限/幂等说明、模型展开、无结果清空、错误重试和窄屏；其结果与自动化测试分开记录。

结构校验不能证明业务权限和状态正确；实际调用验收也不能覆盖所有业务分支。授权、事务和表单边界仍由原领域与集成回归测试验证。普通已认证账号可读契约，读取文档不会改变其接口权限。

流程管理员可通过 `/process-definitions/assignee-options` 读取当前身份源中的可配置审批账号和角色。发布会重新核对有效成员，完整语义见[审批人配置](designer-assignees.md)。

会签模式使用人工节点 `properties.approvalMode=ALL`，默认 SINGLE。任务详情可返回 `countersign.total/completed`，并限制固定责任动作。全员规则、空名单回滚与并发处理见[全员会签](all-countersign.md)。
