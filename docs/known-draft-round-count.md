# 未提交草稿历史轮次误报修复

阶段 51 的真实浏览器验证发现：保存一份从未提交的申请草稿后，首次使用引导显示“有 1 个历史轮次缺少快照”。运营统计的同类提示也包含这些未提交草稿。该问题位于既有服务端读模型，在阶段 52 修复。

最小复现：发布带表单的流程，创建申请并只保存为草稿，然后以 ADMIN 请求该版本的 `/api/v1/system/first-workflow?definitionId=...`。草稿没有提交轮次，但返回 `unrecordedHistoricalRounds=1`。期望未提交草稿不计作缺失提交历史；真正旧审批缺失轮次仍须保留提示。

原复现证据保存在 `/fyoung/tmp/agentflow-selection-known-draft-count.json`，包括读报告和对应的 DRAFT 申请；隔离 H2 数据保留。修复后的隔离副本读取同一申请，内容完全不变，两个入口缺口均变为 0，见 `/fyoung/tmp/agentflow-history-gap-original-repro.json`。

调用链为 `FirstWorkflow / ApprovalOperations → 原查询 Controller → JdbcFirstWorkflowReadAdapter / JdbcApprovalOperationsReadAdapter`。两个适配器使用 `round_no - 已有轮次数量` 推算缺口，而新申请的 `round_no=1` 已预留首轮编号，尚不表示已提交。现有引导专门测试验证了手工插入 APPROVED 且没有轮次的旧数据，没有对正常未提交草稿断言这一指标。

`SubmissionHistoryGapIntegrationTest` 先在旧实现上稳定复现：5 个用例中 3 个失败，两个入口均把预期的 0 返回为 1。修复后新增回归与原有引导、运营测试通过，完整 Java 验证 551 项通过；236 个 Java 文件、386 个命名类型作者信息完整。

两处读适配器通过 `JdbcSubmissionHistoryGapQuery` 共享口径。它是基础设施中的只读查询协作，不修改申请聚合或流程状态，不增加数据库迁移：

- DRAFT 尚未提交，排除预留首轮。
- CANCELLED 可能来自草稿，也可能来自退回或撤回。仅在首轮没有有效快照，且唯一作废审计准确绑定租户、申请和当前聚合版本，正文明确记录 DRAFT → CANCELLED 时排除。
- 缺少、损坏、重复或绑定不符的作废审计不能证明未提交，继续保留缺口提示。
- 退回、撤回、重提及真正旧轮次缺失继续计算；快照按租户、申请和准确版本匹配。缺口不纳入有日期的提交和结论统计，不补造旧历史。

正常缺口在数据库聚合；仅对可能误报的首轮作废读取审计内容，采用集合查询，没有逐申请补查。现有读入口的只读一致性事务覆盖两次查询。

H2 / PostgreSQL 实际接口均覆盖保存草稿、草稿作废、提交后退回或撤回再作废、正常批准。各自保留 5 个合成申请，真实提交 3 轮，作出结论 2 轮，退回率为 50%，撤回排除。复验脚本 `scripts/check_submission_history_gap.py` 仅接受独立本机端口，拒绝主入口 8080 / 8180；保留生成数据。

本阶段未修改前端。前端 218 项及浏览器交互证据沿用阶段 51，不将其记作本轮新跑的测试。缺失或损坏审计的保守提示不等于已确认的数据丢失，仍需结合历史材料核对。
