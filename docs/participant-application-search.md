# 参与者申请检索

普通账号的“申请记录”支持标题/业务单号、当前状态、流程标识与版本、申请人账号和 UTC 创建日期筛选，先加载 30 条摘要，继续加载后续页面。可见范围与原申请详情一致；发起人可进入草稿、退回、撤回详情继续修改。管理员仍使用运营检索及原有 Excel 导出。

## 读取权限

同一租户内，满足以下任一条件可见：

- 当前用户是申请人，或具有 ADMIN 角色。
- 引擎当前任务指派给该用户，或存在该用户/其当前角色的身份关联。
- 引擎历史任务指派给该用户，包括仍在执行中的历史任务记录。
- 真实任务审计记载其执行过 APPROVE、RETURN、REJECT、TRANSFER、DELEGATE、RESOLVE。

PROCESS_ADMIN 不因此获得全租户申请权限。评论、单独认领后释放、未领取的角色候选任务撤回，不建立额外的永久办理权限。实际办理人经过转交、委托、回交或退回重提后仍可读取原申请；这与现有详情规则相同，并非本阶段新增授权。

每页重新判断当前权限，先筛权限和业务条件再分页，不先取固定数量后用 Java 过滤。列表返回的摘要是读取时快照，之后权限或状态仍可能变化；打开详情时由原申请服务重新授权。

## 接口与兼容

`GET /api/v1/applications/search` 接受与[管理员申请检索](application-search.md)相同的查询参数，默认 limit=30，最大 100。按 `created_at DESC, id DESC` 游标分页；游标绑定租户、用户、角色和筛选条件。响应 `items`、可选 `nextCursor`，并设置 `Cache-Control: no-store`。

摘要不含 payload、formSchema、审批意见、引擎实体或写入用的申请版本。未知参数、跨账号或跨条件游标返回 `400 INVALID_APPLICATION_QUERY`；无认证返回 401。不能传入 tenantId 或 actor 扩大可见范围。

原 `GET /api/v1/applications` 数组接口仍保留兼容，在 OpenAPI 标为 deprecated；交互页面不再调用该无界接口。`GET /api/v1/operations/applications` 和 `/export` 继续要求 ADMIN。普通账号没有导出按钮，也不能直接调用管理员导出接口。

## 调用链与职责

`ApplicationSearch.vue → api.searchVisibleApplications → ApplicationSearchController → ApplicationSearchPort → JdbcApplicationSearchAdapter`。

领域读端口接受当前 Actor 与已校验查询条件，返回业务摘要；HTTP 入口负责参数和运营入口角色校验。JDBC 适配器负责组合租户、申请归属、真实办理审计与引擎参与关系。Flowable 7.2 的表结构查询局限在 `FlowableApplicationParticipationSql` 防腐层，复用同一数据库连接，只读查询，不复制任务状态或授权表。

没有新增数据库迁移、权限副本或审批状态转换；实体状态仍由原 DDD 聚合和审批应用服务管理。未来更换引擎或修改详情参与规则时，应同时执行详情与摘要可见性对照测试。

## 已验证

- Java 全量 506 项、前端 182 项、Vue 类型检查与生产构建通过。
- 218 个 Java 文件、356 个命名类型作者检查通过。
- 6 个新增真实集成测试覆盖租户隔离、流程管理员边界、当前用户/角色候选关系、认领释放、转交委托、历史指派、跨轮次办理、同时间分页及翻页时权限变化；管理员检索/导出和个人工作台回归通过。
- H2、PostgreSQL 分别完成 58 个真实 API 操作和专用检索脚本。六类账号在五个办理阶段与原列表授权一致，申请人 30＋5 分页、管理员 38 条、无关账号 0 条；摘要不包含正文。
- 浏览器完成申请人分页、未提交筛选禁止翻页、状态筛选、第二轮详情查看、参与人仅见相关申请、切换无关账号后空结果、普通账号无导出入口。桌面截图核对完成，本阶段没有新增移动端验收。

复验命令：

```bash
python3 -B scripts/check_participant_search.py http://127.0.0.1:8082 --exercise
```

只允许明确指定的独立本机演示端口，拒绝主入口 8080/8180。脚本新增随机流程和合成申请并保留，不删除旧数据。当前验证不等于生产大数据量容量测试；兼容数组接口的淘汰、组织身份、附件、Agent、财务和 SLA 等仍需继续完成。
