# 站内消息中心

站内消息记录审批已经发生的进展，提供全部/未读筛选、未读总数、游标分页、标为已读，以及返回申请或当前待办的入口。消息与对应审批事务一起提交；此阶段不发送邮件、IM 或 Webhook，也不计算 SLA。

## 接收规则

| 业务事实 | 接收人 | 消息类型 |
|---|---|---|
| 首次提交或补正重提成功 | 申请人收到提交确认；实际当前审批人收到待办 | APPLICATION_SUBMITTED / TASK_PENDING |
| 中间节点批准 | 下一节点实际审批人 | TASK_PENDING |
| 最终批准、退回、驳回 | 申请人 | APPLICATION_APPROVED / APPLICATION_RETURNED / APPLICATION_REJECTED |
| 转交、委派、受托回交 | 操作后的实际指派人 | TASK_TRANSFERRED / TASK_DELEGATED / TASK_RESOLVED |
| 释放任务 | 重新可处理该任务的候选人 | TASK_PENDING |
| 撤回申请 | 申请人及撤回前的实际待办接收人 | APPLICATION_WITHDRAWN |

领取不重复发消息。指定用户任务只通知有效的指定账号；未指派任务将候选用户和候选角色取并集，再用实际身份目录过滤、去重。已指派任务不继续向原候选组广播。现阶段目录与演示登录共享同租户有效审批账号，企业组织/岗位/代理目录仍待接入。

消息只保存发生时的申请标题、单号、轮次、节点、操作人和动作类型，不复制表单正文、审批意见、附件或引擎变量。后续修改申请标题不回写旧消息。模板中心的通知文案尚未启用，当前消息使用平台统一文案。

## 权限与阅读语义

消息列表与标记已读都强制绑定当前会话的租户和接收人；管理员只能查看、标记自己的消息。消息不作为申请参与关系的证据，不因此授予申请或任务权限。

点击“查看待办”先查询最新待办，仍在本人队列中才定位任务；已流转则打开申请详情，沿用原授权。如果只是曾经的候选人，任务结束后可能不能继续读取申请，详情显示授权结果。提醒保留其发生时事实，不声称任务仍然等待本人处理。

“查看”与“标为已读”是独立操作，打开申请不会隐式写入阅读状态。已读由 InboxMessage 自身的状态方法决定：只记录第一次阅读时间。数据库条件更新处理多个不同请求键同时标记的竞争；同键请求则复用统一幂等响应。阅读状态不修改申请、流程或审批审计。

## API

- `GET /api/v1/notifications?read=all|unread&limit=30&cursor=...`：返回 `{items, nextCursor?, unreadCount}`。默认 all、30 项，最大 100；未读总数属于当前接收人的整个收件箱。
- `POST /api/v1/notifications/{id}/read`：要求 `Idempotency-Key`，返回保留首次 readAt 的消息；他人或其他租户消息返回 404。

入口拒绝未知参数、非法 read/limit 和其他账号/筛选的游标，返回 400 / INVALID_INBOX_QUERY。分页按 created_at 和唯一 id 双键倒序；同时间消息不漏行。并发新增或已读变化后，列表与总数可能对应不同查询时刻，刷新首页获取最新结果。

消息读取是只读请求，可取消且有 12 秒总时限。切换账号、筛选或刷新会清空旧结果，迟到成功和失败均不回填；加载更多失败保留原记录和游标。已读响应不确定时使用现有原请求恢复入口，不换请求键推测执行结果。

## DDD 与一致性

写链路：ApprovalApplicationFacade / FlowableTaskFacade → ApprovalNotificationService → TaskAudiencePort / InboxRepository。读取链路：NotificationInbox → InboxController → InboxApplicationService → InboxRepository。

InboxMessage 管理自身阅读状态。通知应用服务编排审批结果与实际任务接收人；FlowableTaskAudienceAdapter 负责引擎查询，AuthService 实现现有有效账号端口，JdbcInboxRepository 负责租户/接收人条件和存储。Controller 不查询引擎表，通知服务不改变审批状态，也没有把通知写入审计适配器。

站内消息与业务在同一数据库事务内落库，写服务要求已有事务。通知保存失败会回滚申请、引擎、轮次、审计及当前消息，客户端可按原审批请求协议恢复。事件键由申请 ID、版本、动作和任务 ID 构成，接收人参与唯一约束；统一写幂等与申请版本控制保障并发请求不重复执行，重复编排同一事件也不增加消息。

站内落库没有外部投递步骤，当前不引入异步 worker。未来邮件/IM/Webhook 的 Outbox 投递、重试和人工处置属于独立交付，不能把站内落库等同于外部发送成功。

## 升级和验收

V11 只增加 notification_inbox 及接收人时间、未读索引。V1—V10 不修改，旧申请和既有待办不补发历史消息；升级后真实新动作才产生消息。系统自检会实际查询消息表，返回 IN_APP_ONLY，并明确邮件、IM、SLA 尚未接入。

后端 InboxIntegrationTest 覆盖实际候选组、两级审批、委派/回交/转交、退回/驳回/撤回、无正文泄露、256 字标题、通知失败事务回滚、跨租户/账号、同键并发已读、重复已读、同时间分页与游标上下文。InboxMigrationTest 验证 V10→V11 保留旧业务与迁移记录。前端 notifications.test.mjs 覆盖查询竞态、分页失败重试、去重、只读请求和已读写请求恢复。

独立验收库执行：

```bash
python3 scripts/check-notifications.py http://127.0.0.1:8082
```

脚本创建随机流程与三张申请，验证提交、委派、回交、最终批准、并发已读和撤回提醒；保留第三张待办供浏览器使用，不删除既有数据。站内消息当前随页面进入或手动刷新加载，不使用实时推送。全部标为已读、通知偏好、外部渠道、超时升级和保留策略尚未实现。
