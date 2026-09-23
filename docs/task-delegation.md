# 任务委派与回交

委派把核实工作交给另一名审批人，受托人填写意见后回交，原审批人继续作出批准、退回或驳回决定。转交则把当前任务交给接收人继续审批。两者在页面上分别说明，接收人由服务端目录选择。

## 动作与状态

| 当前任务 | 可执行动作 | 结果 |
|---|---|---|
| 未委派的候选组任务 | 批准、退回、驳回、转交、委派、领取 | 沿用现有任务授权；委派时显式记录操作人为 owner |
| 未委派的已指派任务 | 批准、退回、驳回、转交、委派、释放 | 转交清除旧委派关系，下次委派使用新的操作人 |
| 委派待回交 PENDING | 仅 RESOLVE，必填处理意见 | 任务回到持久化 owner，申请和提交轮次仍为 IN_APPROVAL |
| 已回交 RESOLVED | 原责任人恢复普通审批动作 | 审计保留委派与回交双方、意见和时间 |
| 旧委派记录缺少 owner | 不展示可执行动作 | 返回明确冲突，需要核实原责任人，不推测或自动修改历史 |

状态规则参考 [Flowable 7.2 TaskService 契约](https://javadoc.io/static/org.flowable/flowable-engine/7.2.0/org/flowable/engine/TaskService.html)：delegateTask 进入 PENDING，resolveTask 回到 owner，待回交任务不能直接 complete。平台进一步要求待回交期间不能用退回、驳回、转交、再次委派、领取或释放绕过原责任人。

## API 增量

- `GET /api/v1/tasks` 在原任务数组中新增 `owner?`、`delegationState`（NONE/PENDING/RESOLVED）、`allowedActions`。这些字段供界面展示；写操作仍实时校验权限与状态。
- `GET /api/v1/tasks/{taskId}/recipients` 返回当前租户有效审批账号数组，排除自己。查询先校验 APPROVER、租户与当前任务操作权；待回交任务不提供转交名单。
- `POST /api/v1/tasks/{taskId}/actions` 增加 `RESOLVE`，使用原 `Idempotency-Key`、`expectedVersion` 和 `comment`。回交接收人从任务 owner 读取，不采用客户端传入的 targetUser。
- `TRANSFER`/`DELEGATE` 执行时用同一人员目录复核，拒绝自己和不存在的接收人。当前目录与演示登录共享账号与角色来源；关闭演示登录或其他租户不返回演示账号。企业组织和 IdP 适配仍待实现。
- 操作审计 `action=RESOLVE` 与个人已办 `action=RESOLVE` 均可筛选。回交人保留申请读取权限；已回交后不能继续操作原任务。

| 错误码 | HTTP | 含义 |
|---|---:|---|
| TASK_DELEGATION_PENDING | 409 | 委派待回交，当前动作不能执行 |
| TASK_DELEGATION_OWNER_MISSING | 409 | 旧记录缺少原责任人，不能安全回交 |
| TASK_NOT_DELEGATED | 422 | 普通任务不能执行回交 |
| INVALID_TASK_RECIPIENT | 422 | 接收人不存在、不具备审批资格或为自己 |

回交意见为空沿用 422 / DOMAIN_RULE_VIOLATION；版本、租户和指派关系沿用原冲突与授权契约。成功响应仍为 taskId、action、applicationStatus、version、auditEventId，没有另造业务状态。

## DDD 与事务归属

调用链为 `TaskActions` → `api.taskAction` → 幂等入口/`TaskController` → `FlowableTaskFacade` → 领域状态与 Flowable/JDBC。下游影响任务指派、引擎意见、申请版本、任务审计、已办投影和参与者读取。

`TaskDelegation` 是领域状态值对象，只决定本状态允许的动作，不依赖引擎和人员目录。`TaskRecipientDirectory` 表达有效接收人的查询需求，由当前身份源实现。应用服务承担跨聚合与外部资源编排：校验当前任务、读取人员目录，将状态动作、引擎回交、意见、申请版本和审计放在同一个数据库事务。审计失败时全部回滚。申请自身版本与状态继续由 Application 维护。

回交只记录任务办理，不调用申请批准或提交轮次完成。RESOLVE 的审计接收人是服务端 owner；已办同时展示“办理后状态”和“申请当前状态”，最终批准不会改写回交时的 IN_APPROVAL。幂等响应包含原 auditEventId：同键并发只执行一次，即使原审批人随后批准，重放仍返回当时的回交结果。

本次没有数据库迁移，V1—V10 保持不变。新 RESOLVE 通过已有审计适配器写 actor_id；V10 已发布的旧数据回填动作集合不改写。

## UI 与验证

受托任务显示原责任人与“填写意见并回交”，不显示最终决定按钮。回交意见必填；转交/委派使用服务端接收人下拉框，并处理加载、空名单、失败与重试。任务、账号和版本变化后清除旧表单与名单，取消请求，迟到响应不覆盖新任务。写入沿用全局锁及不确定结果恢复协议。

`TaskDelegationIntegrationTest` 使用真实 HTTP、Flowable 与业务事务，覆盖候选组 owner、转交后再次委派、无效接收人、七种绕过动作、授权、过期版本、空意见、旧缺失 owner、审计回滚、四请求并发与最终批准后的幂等重放。前端 `task-actions.test.mjs` 覆盖任务快照、名单竞态与清空、只读目录请求、回交写请求恢复。

独立验收库执行：

```bash
python3 scripts/check-task-delegation.py http://127.0.0.1:8082
```

脚本创建随机前缀流程和两张申请，完成一条 HTTP 委派/回交/最终批准链路，保留另一张待办供浏览器操作。它不删除已有数据。作者检查、后端全量与前端验证命令见 README；实际结果、PostgreSQL 兼容性和浏览器证据见相邻文档目录的 21 阶段验收。
