# 版本化申请表单

表单是流程定义发布内容的一部分，与流程图共用 `key + version` 和草稿 `revision`。`schemaVersion` 只表示表单格式版本，支持 `1`（基础字段）和 `2`（增加重复明细）；业务表单版本由所属流程定义版本标识。已发布内容不可原地修改，复制新草稿并发布下一版不会改变旧申请。

## 配置与接口

流程定义创建、修改及校验请求可包含 `formSchema`，定义、申请和提交轮次响应也返回该字段。申请写接口只接收 `payload`，表单定义由服务端按当前租户及准确发布版本获取，调用方不能指定另一份表单来绕过校验。

```json
{
  "schemaVersion": 1,
  "fields": [
    {"key": "leaveType", "label": "请假类型", "type": "SELECT", "required": true,
      "options": [{"value": "annual", "label": "年假"}, {"value": "personal", "label": "事假"}]},
    {"key": "startDate", "label": "开始日期", "type": "DATE", "required": true},
    {"key": "days", "label": "请假天数", "type": "NUMBER", "required": true,
      "minimum": "0.5", "maximum": "30", "helpText": "可按半天填写"},
    {"key": "reason", "label": "申请事由", "type": "TEXTAREA", "required": true, "maxLength": 1000},
    {"key": "urgent", "label": "是否紧急", "type": "BOOLEAN", "required": true}
  ]
}
```

| 字段类型 | 保存格式 | 校验 |
|---|---|---|
| `TEXT` / `TEXTAREA` | 字符串 | 可配 `maxLength`，最大 10000；空白文本视为未填 |
| `NUMBER` | 十进制字符串 | 可配字符串 `minimum` / `maximum`；使用十进制精确比较 |
| `DATE` | `YYYY-MM-DD` 字符串 | 校验实际日期，包括闰年 |
| `SELECT` | 选项 `value` 字符串 | 必须属于此发布版本的选项；展示其 `label` |
| `BOOLEAN` | JSON `true` / `false` | `false` 是有效值；未填写与“否”不同 |

数字允许负号、前导零和小数，不接受指数、加号或首尾空格；原字符串最长 80 个字符，精度最多 38 位、小数位最多 18 位。前端不转为 JavaScript `Number`，以免损失精度；服务端不接受 JSON 浮点数字代替这类字段的十进制字符串。金融单据的币种、核算和费用明细仍属于独立财务领域，通用数字字段不代替这些规则。

字段最多 50 个，标识以英文字母开头，仅包含字母、数字、下划线且最多 64 位。字段标识和选项值不可重复，单选最多 50 个选项。`constructor`、`prototype` 与租户、申请、轮次等系统变量保留字不能作为字段标识；`__proto__` 不符合首字符规则。`toString` 等合法字段按对象自身属性读取，不读取继承的原型属性。标题、业务单号、绑定流程版本属于申请自身，不放入表单字段。

服务端严格检查 schema 三层属性白名单：根对象仅接受 `schemaVersion`、`fields`；字段仅接受 `key`、`label`、`type`、`required`、`helpText`、`maxLength`、`minimum`、`maximum`、`options`、`columns`、`maxRows`；明细列只允许基础字段，不能嵌套明细；选项仅接受 `value`、`label`。未知属性即使值为 `null` 也返回 `INVALID_FORM_SCHEMA`，不会被静默删除。例如拼错的 `maximun`、当前尚未支持的 `readOnly` 或 `visibleTo` 都不能保存为已生效的配置。

## 草稿、提交与快照

创建或修改草稿时检查已填写值的类型、范围、长度和字段白名单，允许必填项暂未填写。提交时额外检查必填项；失败不会启动流程、创建轮次或改变申请状态。`null`、缺失和空字符串可表达未填写，原始值不会被静默转换成 `0` 或 `false`。

创建申请时冻结已发布表单；申请补正不允许换绑定版本。每次提交再把表单与当时的 payload 一起冻结到该轮次。详情按申请自身的表单展示，历史按每轮自己的表单展示；不查询最新流程版本来替换旧标签、选项或约束。

申请还在 `runtime_definition_id` 保存创建时解析的实际引擎定义 ID。服务端先选择当前租户已发布定义；仅在没有对应租户定义时，允许使用内置 `expense-reimbursement` v1。表单快照与实际定义来源共同冻结，后续出现同 key、同版本的租户定义不会替换先前绑定的内置定义。补正接口不能修改该 ID；启动时按 ID 查找并再次核对租户、key 和版本。绑定的定义缺失或不匹配时返回 `422 PROCESS_DEFINITION_NOT_FOUND`，不切换到其他来源。

字段校验失败为 `422 FORM_VALIDATION_FAILED`，响应的 `details.fieldErrors` 按字段标识返回稳定错误码：`REQUIRED`、`INVALID_TYPE`、`INVALID_NUMBER`、`INVALID_DATE`、`INVALID_OPTION`、`TOO_LONG`、`BELOW_MINIMUM`、`ABOVE_MAXIMUM`、`UNKNOWN_FIELD`。错误不回显用户填入的正文。配置不合法使用 `INVALID_FORM_SCHEMA`；JSON 绑定失败可能先返回 400。

## 条件与运行时

带表单的定义只能引用已声明字段。校验与发布检查运算符及字面量是否适合字段类型；模拟和 Flowable 执行使用同一领域条件求值语义。数字按十进制比较，文本和单选按文本比较，例如选项 `01` 与 `1` 不相等。字段内容不作为脚本执行，也不能覆盖引擎的租户、申请、轮次等变量。

条件支持单引号或双引号包围的文本。`AND`、`OR` 只有在引号外、作为空白分隔的完整词时才是连接符，`AND` 优先于 `OR`。例如 `reason == 'Research AND Development' AND department == "legal OR finance"` 比较两个完整文本值。未闭合引号、完整引用后的尾随文本会被拒绝；当前没有引号转义语法，可用双引号包围含单引号的文本。原有无引号字面量继续可用，括号、分号及 `${...}`、`#{...}` 等脚本语法仍被禁止。

## 旧数据兼容

V7 在流程定义、申请及提交轮次增加可空 `form_schema_json`，在申请增加可空 `runtime_definition_id`，不改写已使用迁移，不为历史记录补造表单或定义来源。

- `formSchema: null` 表示旧版没有保存表单定义，保留既有 payload 和兼容展示。
- `{ "schemaVersion": 1, "fields": [] }` 表示明确没有业务字段，不能提交额外 payload 字段。
- 定义创建请求省略表单保留旧客户端契约；修改请求省略或传 `null` 保留已配置表单，不会意外删除字段。
- 新申请必须绑定当前租户真实发布定义；仅既有内置 `expense-reimbursement` v1 保留明确的旧版演示兼容入口。

旧申请的 `runtime_definition_id` 为 `null` 时，重提优先从上一提交轮次记录的实际历史实例恢复定义来源；历史查询同时核对租户和申请 ID，再复核定义的 key、版本及租户。已有轮次指向的历史实例或定义不可用时直接报错，不根据当前同号版本猜测来源。

旧申请没有已保存的定义 ID，也没有轮次历史可用时，服务端仅在当前租户定义或允许的内置定义中查找准确 key、版本。只有一个匹配来源时可以启动；两个来源都存在时返回 `409 DEFINITION_BINDING_AMBIGUOUS`，草稿状态和版本保持不变，不启动实例、不创建轮次，也不补写一个猜测的来源。

## UI 与验收

管理员在流程管理配置字段及预览，发布后只读；复制为草稿可修改下一版本。申请人可以保存未填完的草稿、补齐后提交，退回或撤回后沿用原表单补正。所有编辑动作仍受角色、状态、并发版本和未确认请求锁定控制。响应丢失恢复只确认原操作，不重新构造表单正文或自动执行下一步。

`python3 scripts/check-versioned-forms.py` 在运行中的本地演示服务验证定义与表单发布、草稿校验、条件执行、版本隔离和补正历史。脚本创建带随机前缀的本地测试记录，不删除既有数据。自动化、迁移及浏览器结果以本阶段验收记录为准。

可选来源绑定验收使用 `python3 scripts/check-versioned-forms.py http://127.0.0.1:8080 --check-bundled-binding`。此选项会在 `demo` 租户发布固定 key 为 `expense-reimbursement` 的租户 v1，用来验证先前创建的内置申请仍走原流程、新申请采用租户表单。脚本登录后、创建任何验收业务记录前先确认不存在已发布的同名租户定义；前置条件不满足时立即结束。该选项会保留创建的定义和申请，不覆盖或删除已有数据，适用于尚未发布同名模板的独立验收环境；默认随机流程验收不受此限制。

[重复明细](detail-table-forms.md)已支持列配置、行编辑和轮次快照。节点级字段显隐、读写与脱敏策略、附件及复杂布局仍待实现。
