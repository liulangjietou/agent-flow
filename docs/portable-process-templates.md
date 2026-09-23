# 流程模板文件

流程管理员可在“开始使用”“模板中心”或“流程管理 → 导入 / 导出模板”进入模板文件页。此能力用于复用流程配置；企业身份、运行申请和发布历史不随文件迁移。

## 使用

1. 在设计器打开要复用的流程，进入模板文件页，点击“生成模板文件”，再点击“下载流程模板 JSON”。导出包含当前设计器内容，包括未保存修改，不会保存草稿或发布。
2. 在接收环境选择文件。页面显示来源名称、标识、节点和字段数量，可展开审批人及表单预览；此时不写入服务端。
3. 点击“检查模板”，复用服务端的图结构、表单、条件和当前租户审批人检查。网络错误和超时必须重试，不能沿用旧成功状态。
4. 填写目标标识和名称，点击“创建独立草稿”。原设计未保存时，确认框允许取消并保留原内容。已有同标识流程会显示提示：本次仍创建新的草稿，后续发布产生新版本，已有版本和申请不变。
5. 核对审批人、修改配置、运行模拟，再填写变更说明并发布。文件不会自动发布或发起申请。审批人不可用时可先创建草稿进行修改，但发布仍会被阻断。

格式错误、未知版本、未知属性、超限文件整份拒绝，不会删除不认识的配置后继续导入。更换文件、离开页面和账号切换会丢弃读取与预检的迟到结果。生成的下载链接仅在当前页面有效；切换页面或设计内容变化后需重新生成。

## 文件契约

UTF-8 JSON（允许 BOM），最大 1 MiB，格式 `agentflow-process-template`，`formatVersion=1`。

```json
{
  "format": "agentflow-process-template",
  "formatVersion": 1,
  "process": {
    "key": "team-leave",
    "name": "团队请假",
    "graph": {
      "nodes": [
        {"id": "start", "name": "开始", "type": "START", "properties": {}},
        {"id": "review", "name": "经理审批", "type": "USER_TASK", "properties": {"assigneeRule": "role:MANAGER"}},
        {"id": "end", "name": "结束", "type": "END", "properties": {}}
      ],
      "edges": [
        {"id": "e1", "source": "start", "target": "review", "condition": "", "defaultBranch": false},
        {"id": "e2", "source": "review", "target": "end", "condition": "", "defaultBranch": false}
      ]
    },
    "formSchema": {"schemaVersion": 1, "fields": []}
  }
}
```

- 只含 `key/name/graph/formSchema`；不导出定义 ID、租户、状态、版本号、发布记录、申请、审计、账号凭证。角色和指定审批账号是配置的一部分，分享前应核对。
- 最多 200 节点、400 连线；节点只允许开始、结束、人工审批和排他分支。节点属性白名单为 `x/y/assigneeRule/approvalMode`，位置在 0 至 1000000 之间。不接受服务任务、脚本或未支持扩展。
- 来源标识最多 128 字符、名称最多 128 字符；节点标识最多 128、名称最多 256、条件最多 4000 字符。创建时目标标识以字母开头，最多 64 字符，可含字母、数字、下划线、短横线。
- 表单复用现有六种字段和配置规则，最多 50 字段。金额边界是十进制字符串，不转换为浮点数。`formSchema: null` 与空字段表单保持区别。
- 文件不包含目录模板的分类、样例、说明或复制来源记录，不会上传到内置模板目录；导入结果位于当前租户的“流程管理”。不接受 BPMN XML 或其他工作流产品的文件。

文件契约的限制不改变既有 API 的兼容范围。旧定义如包含格式不支持的扩展，导出会报错，不能静默丢弃扩展。

## DDD 与安全边界

文件读取、格式检查、下载、预览和未保存确认属于表示层，实现在 `portableTemplate.ts`、`PortableTemplate.vue`。App 只编排创建后打开设计器，调用既有 `POST /api/v1/process-definitions`。

`DefinitionApplicationService.create` 继续编排领域校验和仓储；`DefinitionDraft` 负责聚合身份与生命周期。审批人目录检查属于发布应用服务。没有新增 Java 领域分支、数据库迁移、文件上传存储服务或独立引擎部署入口。

预检使用既有 `POST /process-definitions/validate`，不写库。创建要求 `ADMIN` 或 `PROCESS_ADMIN`，租户取自认证，使用原 `Idempotency-Key` 和请求恢复机制。即使修改前端文件检查，也不能绕过服务端授权、条件白名单和发布校验。

## 验证

前端请求测试覆盖配置精确往返、私有响应字段排除、未知属性/版本、畸形表单、UTF-8 大小、文件切换、迟到结果、预检超时、网络恢复、幂等创建及恢复。完整后端门禁检查现有创建、授权和发布行为。

在独立 H2 / PostgreSQL 演示库运行：

```bash
python3 scripts/check-portable-templates.py http://127.0.0.1:8082 /fyoung/tmp/portable-template-evidence
```

输出目录必须不存在。脚本创建随机标识的来源、导入、后续版本和无审批人草稿，验证实际审批、原版本不变、权限和幂等；输出 JSON 文件供浏览器验收，保留所有合成数据。不要对主演示数据执行。

本阶段不包含附件打包、组织同步、模板市场上传、签名验真、外部格式转换或模板依赖自动安装。
