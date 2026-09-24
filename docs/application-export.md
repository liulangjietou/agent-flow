# 管理员申请摘要导出

ADMIN 在“申请记录”完成查询后，点击“生成 Excel”，成功后点击“下载 Excel”。导出本次条件的全部匹配记录，不受页面已加载 30 条或后续分页影响。每次最多 10,000 份，超过时整次拒绝并提示缩小日期、流程或申请人范围，不生成截断文件。

修改筛选、重新查询、切换账号和离开页面会取消等待并释放旧下载链接。失败清除旧文件，允许再次生成；请求最多等待 60 秒，取消等待不保证服务端计算已中断。普通账号和仅有 PROCESS_ADMIN 的账号均没有此导出权限，原申请详情授权不变。

## 文件内容

文件名为 `agentflow-applications.xlsx`，包含两张工作表：

- **申请记录**：申请标识、业务单号、标题、流程标识、流程版本、申请人账号、状态代码、当前轮次、创建时间与更新时间（UTC）。创建时间倒序，同时间按标识倒序；第一行冻结并提供筛选。
- **导出说明**：租户、导出账号、生成开始时间（UTC）、实际记录数、全部筛选和范围说明。空条件表示不限制该项；日期上界记录为次日零点、不包含。

查询只读取摘要列，没有表单正文、字段权限配置、评论、审批意见或引擎内部标识。空结果也返回带表头、记录数为 0 的有效工作簿。文件是单次查询结果，之后的审批动作不会更新已生成文件；它不是逐轮审计归档或有签章的凭证。

所有单元格写成 Excel 字符串类型，不创建公式、超链接、宏或外部引用。长单号与前导零、逗号、引号、换行和 Unicode 保留原值。OOXML 的字面转义序列（例如 `_x000D_`）先转义下划线，避免打开时变成回车；XML 禁用控制字符用 OOXML 字符表示编码。

生成结果只在服务端和当前浏览器内存中暂存，不写服务端业务文件目录。下载后的文件由操作者管理，退出登录不会删除已经保存到本机的文件。

## API 与职责

`GET /api/v1/operations/applications/export`，仅当前租户 ADMIN。

支持 `q`、`status`、`processKey`、`definitionVersion`、`applicant`、`from`、`to`，与[管理员申请检索](application-search.md)完全同义；不接受 `limit`、`cursor`、租户或排序覆盖参数。

| 结果 | 契约 |
|---|---|
| 200 | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，完整二进制工作簿 |
| 400 | `INVALID_APPLICATION_QUERY`，筛选非法或包含分页等未知参数 |
| 401 / 403 | 未认证 / 当前主体不是 ADMIN |
| 422 | `APPLICATION_EXPORT_LIMIT_EXCEEDED`，超过 10,000 份 |
| 429 | `APPLICATION_EXPORT_BUSY`，当前服务实例正生成另一份导出 |
| 503 | `APPLICATION_EXPORT_FAILED`，工作簿生成失败 |

成功响应带 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、固定安全文件名的 `Content-Disposition` 和完整长度。文件全部生成后才提交响应，业务错误使用原 JSON 错误协议；该只读 GET 不需要幂等键。

调用链为 `ApplicationSearch.vue → ApplicationExportController → ApplicationExportService → ApplicationSearchPort / ApplicationWorkbook`。Controller 负责一次入口授权和筛选解析，应用服务负责容量准入、摘要查询及表示生成；原 JDBC 端口一次读取至多 10,001 行用于识别超限，不循环请求列表分页、不加载聚合正文，不在文件下载期间持有数据库事务。`ApplicationWorkbook` 属于服务端输出表示，依赖 Apache POI 5.5.1；领域模块不依赖 Excel 库。

每个服务实例同时只允许一个申请工作簿生成，避免并发工作簿大量占用审批服务内存；成功或失败都释放容量。[审计摘要导出](audit-export.md)使用独立容量，两类可同时生成。这个限制是资源保护，不是分布式任务调度或生产容量结论。没有新增迁移、审批事件或状态，未实现异步大数据导出、定时分发、导出留存或完整审计档案。

## 验证

后端测试使用实际 SQL，并重新读取生成的工作簿核对租户/角色、36 条完整结果、组合条件、UTC 日期边界、特殊文本、空结果和业务数据不变；单测验证恰好 10,000 条完整返回、10,001 条拒绝及并发失败后的容量释放。前端验证筛选冻结、重复点击、取消/超时/迟到响应、二进制读取和错误提示。

真实数据验证只对隔离的本机演示环境执行：

```bash
python3 scripts/check-application-export.py \
  http://127.0.0.1:8082 /fyoung/tmp/agentflow-export-example --exercise
```

输出目录必须不存在。脚本创建 35 份合成申请（34 草稿、1 作废），验证全部及分状态导出、正文排除、身份拒绝、未知参数和导出前后申请/轮次/审计/轨迹逐项相同；保留 `.xlsx`、报告和测试数据。H2、PostgreSQL 均完成这项验证及全部 52 个实际接口契约。

浏览器已验证生成链接、筛选变更清理、断网重试与普通账号隔离。真实文件通过 API 下载后解包核对；浏览器点击下载链接未读取系统下载目录，因此不以点击动作证明操作系统保存位置。默认与实际 693 CSS 像素布局有截图，实际 520 CSS 像素无横向溢出。

格式选择依据：[Apache POI 5.5.1 发布页](https://poi.apache.org/download.html)、[POI 单元格与工作簿指南](https://poi.apache.org/components/spreadsheet/quick-guide.html)。CSV 对公式式文本的跨软件处理存在差异，因此这里使用明确的 Excel 文本单元格，参考 [OWASP CSV Injection](https://community.owasp.org/attacks/CSV_Injection)。
