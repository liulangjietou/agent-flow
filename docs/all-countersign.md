# 全员会签

本阶段按已确认的规则实现：一个人工审批节点的所有成员同意后才流转；任一成员驳回，整轮审批结束。本篇描述 SINGLE/ALL；新增 ANY/PERCENT 见[会签策略](countersign-policies.md)。设计器支持展示、修改、撤销/重做、自动保存、版本比较和发布后只读。

## 业务规则

- `graph.nodes[].properties.approvalMode` 仅适用于 USER_TASK；本篇关注 `SINGLE` 和 `ALL`，完整取值见会签策略文档。旧定义没有此属性时保持 SINGLE，不因加载设计器而补写默认值。
- SINGLE 保留原行为：指定账号直接处理；角色候选组由一人处理。
- ALL 在节点实际激活时，从当前租户有效审批目录解析 `assigneeRule`，去重并固定名单，每人一张独立待办。指定一个账号时是一人的会签节点。
- 后续角色变化不自动增删已激活节点的任务；下一节点或新轮次重新解析。有效审批资格由当前身份源及已启用的本地组织目录核对，真实企业身份与组织接入仍独立验收。
- 全部同意后才进入下一节点；只有整个流程结束，申请才变为 APPROVED。任一 REJECT 终止整轮，已同意的历史意见保留。RETURN、申请人 WITHDRAW 同样终止所有剩余任务，重提产生新轮次。
- 保留 DELEGATE → RESOLVE 协助回交，最终决定仍由原成员作出。会签任务禁止 TRANSFER、RELEASE、CLAIM，服务端和 UI 一致限制，避免替换或遗漏固定名单中的责任人。
- 节点激活时无人可审批返回 `422 COUNTERSIGN_NO_MEMBERS`。提交或上一节点审批的本次事务整体回滚，不跳过节点、不新增成功审计或通知。

## DDD 职责与执行链路

调用方是设计器发布入口和任务动作接口。定义领域校验器约束模式；BPMN 发布适配器将 ALL 生成为 Flowable 并行多实例任务。`FlowableCountersignMembers` 从审批目录读取有效成员，保存在该节点的多实例根执行局部变量中。Flowable 会反复求值集合，根作用域保证同一节点只读取一次目录，下一节点不沿用旧名单。

`CountersignProgress` 表达会签事实和固定责任的动作约束。任务应用服务编排任务授权、领域动作、引擎执行、申请版本、轮次、审计和通知。Flowable 原生任务与完成计数是唯一运行事实，不新建投票表、不复制任务状态，也没有数据库迁移。表单字段仍在 formData 内，不能覆盖引擎的会签成员或计数变量。

通知服务在 APPROVE 前记录已有任务 ID，成功后只提醒新产生的待办；部分会签同意不重复提醒其他成员。委派/回交只通知对应任务，不向其他会签任务错发消息。最终批准只通知一次申请人。

全员会签的显式加减签已在后续完成：新增人员产生必要任务，减签只取消其他未决责任，已完成意见和节点初始名单保留，见[加减签办理](countersign-membership.md)。任一人通过与比例通过也已完成本地验收，见[会签策略](countersign-policies.md)；其他流程能力以[当前剩余清单](remaining-local-work.md)为准。

## 接口与并发

任务详情 `/api/v1/tasks/{id}` 和兼容任务列表中的 ALL 任务增加可选字段：

```json
{"countersign":{"total":2,"completed":1,"mode":"ALL","required":2},"allowedActions":["APPROVE","RETURN","REJECT","DELEGATE"]}
```

SINGLE 任务不返回 countersign。数字来自引擎实际状态，委派回交不计为同意。摘要分页仍按原契约返回，打开详情时读取当前进度和版本。

所有会签任务共享申请的 expectedVersion。两人同时提交同一版本时，至多一项成功，另一项返回 409，任务、计数、审计和消息随事务回滚；刷新后使用新版本、新幂等键重试。结果未知的请求先按原键恢复，不自动重放为新的审批决定。禁止变更责任的动作返回 `409 COUNTERSIGN_ASSIGNMENT_FIXED`；已终止任务返回 404。

流程模拟只计算路径，不创建会签任务或模拟成员表决；页面明确说明其范围。

## 验证

```bash
# 只对独立本机演示库执行，脚本保留全部随机验收数据
python3 scripts/check-all-countersign.py http://127.0.0.1:8082 --exercise
```

`CountersignIntegrationTest` 覆盖：多人全通过、实际完成数、固定责任和无关账号拒绝、委派回交、通知去重与准确受众、已有人同意后的驳回、退回/撤回后重提、名单冻结/下一节点刷新、空名单提交与转移回滚、同意与同意/驳回竞争。领域测试检查非法模式，前端检查旧图不被默认值改写及模式往返保存。

独立 H2、PostgreSQL 实际 HTTP 验收与浏览器操作的结果记录在工程外文档 `26-全员会签阶段验收.md`。完整回归与 CI 状态分开记录。

技术依据：[Flowable 官方多实例说明](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)；当前实现还核对了项目所用 Flowable 7.2.0 的 MultiInstanceActivityBehavior、ParallelMultiInstanceBehavior 源码，未设置提前完成条件。
