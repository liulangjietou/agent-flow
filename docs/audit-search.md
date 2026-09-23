# 管理员操作审计检索

管理员从侧栏“操作审计”跨申请检索当前租户的真实申请、任务操作。可按操作人账号、动作、来源、关联申请 UUID、申请当前标题或业务单号、UTC 操作日期组合查询，展开事件标识并进入现有申请详情。

## 职责与授权

调用链：`AuditSearch.vue → api.searchAudit → AuditSearchController → AuditSearchPort → JdbcAuditSearchAdapter → audit_event / approval_application`。

- 入口校验 ADMIN 角色、筛选白名单和游标上下文；租户只能取自认证主体。PROCESS_ADMIN 和普通账号不可跨申请检索审计。
- 领域模块仅声明只读查询端口与摘要，不引入新的审批状态或引擎判断；JDBC 适配器负责数据库投影和索引查询。
- 申请下钻复用详情接口的独立授权。查询不读取表单正文、审批意见或完整审计 JSON，不触发审批与任务写操作。
- `actor_id` 同时索引新申请与任务操作。个人已办查询仍限制 Task 和已办动作，不会把创建、修改或提交申请算入已办。

## 查询契约

`GET /api/v1/operations/audit`，响应禁止缓存。

| 参数 | 规则 |
| --- | --- |
| q | 最多 100 字符；关联申请当前标题或业务单号大小写不敏感的字面量包含，`% _ !` 没有通配含义 |
| actor | 最多 128 字符的精确操作人账号 |
| action | CREATE、REVISE、SUBMIT、WITHDRAW、CANCEL、CLAIM、RELEASE、TRANSFER、DELEGATE、RESOLVE、RETURN、REJECT、APPROVE |
| source | Application / Task |
| applicationId | 完整 UUID，只匹配同租户可关联申请 |
| from / to | UTC 日期 YYYY-MM-DD，包含首尾两天；缺省不限制对应一侧 |
| limit | 默认 30，范围 1–100 |
| cursor | 绑定租户、账号、角色与已提交筛选；修改筛选后须重新查询 |

按事件 `occurred_at DESC, id DESC` 使用 keyset 分页，数据库最多返回 limit + 1 条。没有全库总数，也不声称跨页数据库快照；新事件需重新查询，申请当前标题变化可能影响关键词命中。页面只显示已加载数量。`APPROVE` 是一次任务同意操作，不代表整单已经批准。

空元数据沿用服务器 NON_NULL 序列化规则，可以省略；前端显示“未记录 / 未关联申请”，不推断动作与操作人。事件按认证租户保留，关联申请通过同租户 LEFT JOIN；无 application_id 的历史 Application 可使用原聚合标识关联，历史 Task 不从正文猜测申请归属。申请关键词与申请 ID 只匹配已关联记录。

## V16 升级

增加 `(tenant_id, occurred_at DESC, id DESC)` 索引。仅对 actor_id 为空、同租户关联存在且正文中的 applicationId/action 与索引事实一致的 Application / Task 事件补齐明确操作人。Application 还须核对聚合标识。拒绝未知动作、错误正文、缺失、空白、超长或含控制字符的账号。

不改写原审计正文、事件 ID、时间、动作、申请关联和已有操作人，不修改旧迁移。无法核对的历史记录仍保留，操作人筛选可能不命中这些记录。

## 验证

- Maven 全量 verify：484 项，0 失败 / 错误 / 跳过；其中包含角色与租户边界、未关联和缺失元数据、组合条件、UTC 日界、字面量特殊字符、同时间分页及插入新事件、游标跨主体/条件拒绝、迁移原始列保护和重复执行。
- 前端请求测试 177 项及类型检查、生产构建通过；新增状态测试覆盖账号切换、迟到响应、条件冻结、重复翻页、错误恢复、超时和 API 参数。
- H2 / PostgreSQL 分别完整执行 53 个 OpenAPI 操作，验证真实创建、修改、提交、批准、撤回、作废审计及查询分页；静态契约为 92 个 schema、19 个请求样例。
- 浏览器验证管理员翻页、联合筛选、事件标识、申请下钻、无效 UUID、空结果、断网重试，实际 1024 / 520 CSS 像素无横向溢出；临时视口与网络覆盖已恢复。
- 类作者扫描：201 Java 文件、327 个具名类型，全部包含 `owlzhangfq@gmail.com`。

当前功能是申请与任务操作审计检索，不包含登录、流程设计操作等尚无统一事件存储的管理日志。
