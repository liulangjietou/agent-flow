# 已批准报销撤销的申请人通知

2026-10-07，U07 通知补充完成本地实现及固定包验证，环境验收继续开放。[机器证据](evidence/approved-expense-revocation-notification-20261007.json)记录原失败和执行结果。本补充仍归 U07，没有拆增任务。

原规范 05:527 要求撤销事件被通知消费。原测试只检查状态和资源，未检查申请人收件，先用真实 HTTP 复现“撤销成功但消息为零”。调用链为报销确认 → ApprovalApplicationFacade.revokeBusiness → ApprovalNotificationService.revoked → 原 InboxRepository 与 NotificationDispatchPlanner。申请生命周期事实的通知归审批应用层；状态、资源、审计、站内消息和外发意向共用原事务。

专用 APPLICATION_REVOKED 类型只通知申请人，保持真实财务操作者和轮次，不复制财务撤销原因，也不增加旧任务或其他人的权限。原请求回放不再插入消息，回滚测试确认没有部分消息。页面显示“申请已撤销”，打开当前授权申请详情，不读取已结束任务。渠道偏好和同意代次仍由原投递链路核验。

39 项 Java 范围、完整前端 1,526 项、类型检查、构建和 OpenAPI 通过；组合去重 Java 5,334 项，本次只重跑相关范围。固定包 `11390692c7efdfd62e713e2faed4edc95c8ada87e43b9f50140cceeb4c480cb6` 经 4 次启动、77 次业务 HTTP；升级前后 281 表、381 行保持。申请人只收到一条消息，其他三种身份无该通知；强退后原消息及原键回放保持。发票、预算释放、凭证停用和原批准证据继续通过。全部外部对端为回环合成服务，真实渠道待验。
