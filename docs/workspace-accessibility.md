# 详情页签与结果焦点

2026-10-07：待办详情和申请历史共用标签页组件，原业务面板和权限规则继续由父页面负责。

- Tab 进入当前页签，左右方向键及 Home/End 移动焦点；Enter/Space 明确激活。未激活面板不会加载数据。
- 页签和面板通过稳定 ID 关联，具有 tablist/tab/tabpanel、aria-selected、aria-controls、aria-labelledby；只显示当前面板。
- 电子签填写/执行期间保持禁止切换，外部切换申请不会被迟到焦点覆盖。
- 审批完成或失败后，焦点移到可读结果提示；其他账号、新任务或已切换的页面不被打断。

原实际模板及处理函数先复现三项失败；修复后七项交互与相关范围共 126 项通过。类型检查、构建及 OpenAPI 通过。见 [机器证据](evidence/workspace-tabs-20261007.json)。真实浏览器与屏幕阅读器仍需补验，组件运行不替代设备验收。

手动激活遵循 [W3C APG 标签页模式](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) 中对按需加载面板的建议。
