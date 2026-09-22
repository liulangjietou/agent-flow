# 审批轨迹与操作审计

待办详情的“时间线”“审计记录”，以及申请记录详情的“审批轨迹”“操作审计”，使用相同查询接口和申请读取权限。申请人、管理员及符合现有参与者规则的用户可读取，不能通过历史接口扩大申请可见范围。

## 查询接口

- `GET /api/v1/applications/{id}/timeline`：按发生时间升序展示流程节点、实际操作及轮次事实。
- `GET /api/v1/applications/{id}/audit`：按发生时间降序展示真实追加的操作事件。

均返回 `{ items: HistoryEvent[], nextCursor }`，没有下一页时 nextCursor 为空。两接口支持 `roundNo`、`limit`、`cursor`；limit 默认 50、最大 100。audit 另外支持 `action`、`from`、`to`，时间使用 ISO 8601 Instant。界面按当前设备时区输入，再转换为带时区的时间传给服务端。

动作筛选采用白名单：CREATE、REVISE、SUBMIT、WITHDRAW、CLAIM、RELEASE、TRANSFER、DELEGATE、RETURN、REJECT、APPROVE。起始时间不能晚于截止时间；轮次和页大小必须在允许范围内，非法游标返回 400。查询不接受调用方指定 SQL 列名或任意排序表达式。

事件包含稳定 id、occurredAt、source、action 和本次排序后的展示 sequence；按记录实际具有的信息返回轮次、操作人、接收人、说明、申请版本、前后状态和节点关联。sequence 用于展示，不能作为历史持久化事件顺序的证明。同一时间发生的记录仍以稳定来源标识排序，分页不能丢弃同时间记录。

## 数据来源与含义

| source | 实际来源 | 展示含义 |
|---|---|---|
| APPLICATION_AUDIT | 申请审计表 | 从本版本开始追加创建、修改、提交和撤回操作 |
| TASK_AUDIT | 任务审计表 | 领取、释放、转交、委托及审批决定 |
| PROCESS_HISTORY | Flowable 历史 | 开始、人工任务、条件网关、结束等实际经过节点的进入与结束 |
| SUBMISSION_SNAPSHOT | 提交轮次记录 | 旧版提交记录及本轮最终结论，明确不是补造的审计事件 |

任务 APPROVE 表示本次审批通过，可能仍有下一个节点。NODE_ENDED 只表示节点结束：退回、驳回或撤回同样可能结束任务，不据此推断“申请已批准”。节点历史不会将最后 assignee 冒充为进入或离开节点的实际操作人。

新 Task 审计保存当时的 applicationId、roundNo、processInstanceId、nodeId、nodeName；TRANSFER、DELEGATE 另存请求中的 targetUser，其他动作不带接收人。新审计的前后申请状态在状态变更前后采集，不从当前申请状态倒推。

旧事件没有记录的接收人、前后状态等字段保持缺失。旧 Task 审计只有 taskId 时，必须通过已核实的历史任务关联申请；引擎历史已缺失就不按业务号或当前处理人猜测。没有提交快照的旧轮次不补写当前 payload，接口也不返回申请正文或引擎变量。

## DDD 职责与升级

申请聚合继续维护状态规则；应用写服务在原事务内收集操作事实，交给审计端口追加保存。审计写入失败与申请、轮次、流程操作一同回滚。

独立的 `ApprovalHistoryQueryService` 组合轮次仓储、流程历史端口和审计查询端口。Flowable、JDBC 适配器负责隔离外部存储结构，Controller 不直接查询引擎表。权限入口复用申请详情授权。

V5 给审计表增加 application_id、action 查询关联列及申请范围索引，保持 V1—V4 迁移不变。旧 Application 事件可依据已有 aggregate_id 建立申请关联；旧任务事件和缺失动作不靠猜测回填。

当前在已授权的单申请范围内合并历史并分页，不扫描全租户审计。大量轮次或长历史仍需要后续容量测试和数据库分页投影；本实现不能据此宣称通过大规模历史流压测。

## 验证入口

根目录执行作者检查和 Maven verify；前端执行 npm run build。新回归入口为 `ApplicationHistoryIntegrationTest`、`LifecycleAuditIntegrationTest`；保留撤回、补正、授权和流程定义回归。最终通过数量、数据库升级及浏览器证据在阶段验收文档中记录。
