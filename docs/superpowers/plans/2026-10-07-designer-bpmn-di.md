# W03 设计器图形导出

来源：原《03-数据与接口契约》§4.3 第 358 行要求节点坐标及自动路由拐点映射为 BPMN DI。

已确认的调用链：画布的 designerGraph / designerLayout → Graph 草稿 → 定义保存与发布 → FlowableDefinitionDeploymentAdapter.RestrictedBpmnWriter → Flowable 部署。节点 x/y 已随 properties 保存，连线拐点目前只供画布计算；发布 XML 直接以 process/definitions 结束，没有 BPMNShape/BPMNEdge。DefinitionDiffService 已排除 x/y 的语义比较，但尚无拐点契约。

这是独立于 W02 画布操作的导出缺口。应在图模型中保存经过边界校验的图形数据，由部署适配器生成 DI；不把画布尺寸和 XML 生成放入审批领域行为，不改变分支顺序、条件或已发布的旧定义。

- [ ] 先通过保存、发布及 Flowable 读取图形信息的测试复现缺失。
- [ ] 完成节点边界和连线拐点的保存、校验与旧草稿兼容。
- [ ] 发布时生成 BPMNShape、BPMNEdge 与 waypoint，坐标变化不产生语义差异。
- [ ] 范围回归、安装包验收与本地合并；真实浏览器仍遵守既有门禁。
