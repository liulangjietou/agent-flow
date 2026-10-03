#!/usr/bin/env python3
"""从已核对的项目事实绘制可编辑 SVG；PNG 由 SVG 渲染器另行导出。"""

import argparse
import html
import json
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAVY = "#193e73"
BLUE = "#168dc5"
GREEN = "#0d9766"
ORANGE = "#e88720"
PURPLE = "#8065b7"
FONT = "'PingFang SC','Heiti SC','Microsoft YaHei','Noto Sans CJK SC',sans-serif"


def units(value):
    """中文按完整字宽计数，英文标点按半宽计数，保留显式换行。"""
    return sum(1 if unicodedata.east_asian_width(char) in "WF" else 0.55 for char in value)


def wrap(value, width, size):
    """只在需要时换行，英文单词尽量完整保留。"""
    lines = []
    remaining = value
    while units(remaining) * size > width:
        cut = 1
        while cut < len(remaining) and units(remaining[:cut + 1]) * size <= width:
            cut += 1
        space = remaining.rfind(" ", 0, cut + 1)
        if space > cut * 0.55:
            cut = space
        lines.append(remaining[:cut].rstrip())
        remaining = remaining[cut:].lstrip()
    if remaining:
        lines.append(remaining)
    return lines


class Drawing:
    """集中处理文字转义和基础形状，两张图共用同一套视觉语言。"""

    def __init__(self, width, height, title, snapshot):
        self.width = width
        self.height = height
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc">',
            f"<title id=\"title\">{html.escape(title)}</title>",
            f'<desc id="desc">AgentFlow 源码快照 {html.escape(snapshot["baselineCommit"][:7])}，已实现、可选配置和待完成边界分别标注。</desc>',
            "<defs>",
        ]
        for color in [BLUE, GREEN, ORANGE, PURPLE]:
            key = color[1:]
            self.parts.append(
                f'<marker id="arrow-{key}" markerWidth="10" markerHeight="8" refX="9" refY="4" '
                f'orient="auto" markerUnits="strokeWidth"><path d="M0,0 L10,4 L0,8 Z" fill="{color}"/></marker>'
            )
        self.parts.extend(["</defs>", f'<g font-family="{FONT}">'])
        self.rect(0, 0, width, height, "#ffffff", "#ffffff", radius=0)

    def rect(self, x, y, width, height, fill, stroke, radius=6, dashed=False):
        extra = ' stroke-dasharray="9 6"' if dashed else ""
        self.parts.append(
            f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="1.6"{extra}/>'
        )

    def text(self, x, y, value, size=24, color=NAVY, bold=False, anchor="start"):
        self.parts.append(
            f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" '
            f'font-weight="{700 if bold else 400}" text-anchor="{anchor}">{html.escape(value)}</text>'
        )

    def arrow(self, points, color=BLUE, dashed=False, label=None, label_at=None):
        coordinates = " ".join(f"{x},{y}" for x, y in points)
        extra = ' stroke-dasharray="9 6"' if dashed else ""
        self.parts.append(
            f'<polyline points="{coordinates}" fill="none" stroke="{color}" stroke-width="3" '
            f'marker-end="url(#arrow-{color[1:]})" stroke-linejoin="round"{extra}/>'
        )
        if label and label_at:
            x, y = label_at
            self.rect(x - units(label) * 10, y - 22, units(label) * 20 + 12, 28, "#ffffff", "#ffffff", 0)
            self.text(x, y, label, 20, color, anchor="middle")

    def box(self, x, y, width, height, title, lines, color=BLUE, tint="#f0f9ff", dashed=False):
        self.rect(x, y, width, height, tint, color, dashed=dashed)
        compact = height < 130
        heading_size, body_size = (24, 20) if compact else (27, 23)
        headings = wrap(title, width - 36, heading_size)
        row_y = y + (31 if compact else 38)
        for line in headings:
            self.text(x + 18, row_y, line, heading_size, color, True)
            row_y += 28 if compact else 34
        row_y += 6 if compact else 10
        for value in lines:
            for line in wrap(value, width - 36, body_size):
                if row_y > y + height - 10:
                    raise ValueError(f"图形文字超出边界：{title} / {value}")
                self.text(x + 18, row_y, line, body_size)
                row_y += 27 if compact else 33

    def diamond(self, x, y, width, height, lines, color=BLUE):
        points = f"{x + width/2},{y} {x + width},{y + height/2} {x + width/2},{y + height} {x},{y + height/2}"
        self.parts.append(f'<polygon points="{points}" fill="#fff9ee" stroke="{color}" stroke-width="2"/>')
        start = y + height / 2 - (len(lines) - 1) * 15 + 8
        for index, line in enumerate(lines):
            self.text(x + width / 2, start + index * 32, line, 24, color, True, "middle")

    def save(self, path):
        self.parts.extend(["</g>", "</svg>"])
        path.write_text("\n".join(self.parts) + "\n", encoding="utf-8")


def panorama(spec, output):
    """绘制五栏架构、治理横条、业务闭环和部署底座。"""
    drawing = Drawing(3072, 2360, spec["title"], spec)
    drawing.text(1536, 83, spec["title"], 58, NAVY, True, "middle")
    drawing.text(1536, 136, spec["subtitle"], 31, anchor="middle")
    drawing.text(3048, 28, f'源码快照 {spec["snapshotDate"]} · {spec["baselineCommit"][:7]}', 19, "#627c9e", anchor="end")
    drawing.rect(24, 172, 3024, 125, "#edf6ff", "#b9d9ef")
    drawing.text(46, 215, "安全与治理贯穿全链路", 31, bold=True)
    for index, (title, detail) in enumerate(spec["security"]):
        x = 522 + index * 498
        drawing.text(x, 214, title, 29, bold=True)
        drawing.text(x, 259, detail, 23)
    drawing.text(32, 334, "统一前后端：各栏表示职责与协作；实际业务按照已发布定义及当前权限执行", 25, "#426792", True)
    for index, pillar in enumerate(spec["pillars"]):
        x = 24 + index * 608
        drawing.rect(x, 355, 592, 1436, "#ffffff", pillar["color"])
        drawing.rect(x, 355, 592, 110, pillar["color"], pillar["color"])
        drawing.rect(x + 16, 374, 47, 59, "#ffffff", "#ffffff")
        drawing.text(x + 39, 418, str(pillar["number"]), 44, pillar["color"], True, "middle")
        drawing.text(x + 78, 400, pillar["title"], 33, "#ffffff", True)
        drawing.text(x + 78, 443, pillar["subtitle"], 21, "#ffffff")
        for card_index, card in enumerate(pillar["cards"]):
            y = 483 + card_index * 257
            drawing.box(x + 14, y, 564, 239, card["title"], card["lines"], pillar["color"], pillar["tint"], card["optional"])
        if index < 4:
            center = x + 600
            drawing.arrow([(center - 7, 988), (center + 8, 988)], BLUE)
            drawing.arrow([(center + 8, 1164), (center - 7, 1164)], GREEN)
    drawing.rect(24, 1810, 3024, 62, "#fbfdff", "#c9deee")
    drawing.text(44, 1850, "图例", 25, bold=True)
    drawing.arrow([(150, 1840), (222, 1840)], BLUE)
    drawing.text(245, 1850, "调用与协作", 24)
    drawing.arrow([(518, 1840), (590, 1840)], GREEN)
    drawing.text(613, 1850, "真实结果反馈", 24)
    drawing.rect(930, 1829, 52, 23, "#ffffff", PURPLE, dashed=True)
    drawing.text(1002, 1850, "可选能力，须明确配置", 24)
    drawing.text(1790, 1850, "批准 ≠ 已过账 ≠ 已付款 ≠ 已核销 ≠ 已归档", 27, ORANGE, True)
    drawing.rect(24, 1891, 3024, 171, "#fbfdff", "#a9c8e4", dashed=True)
    drawing.text(46, 1928, "业务闭环：从模板到运行与运营反馈", 28, bold=True)
    for index, (title, detail) in enumerate(spec["journey"]):
        x = 44 + index * 430
        drawing.box(x, 1947, 410, 95, f"{index + 1}  {title}", [detail], BLUE, "#f3f9ff")
        if index < 6:
            drawing.arrow([(x + 413, 1994), (x + 429, 1994)], BLUE)
    for index, (title, detail) in enumerate(spec["foundations"]):
        y = 2082 + index * 75
        drawing.rect(24, y, 3024, 61, "#f2f8ff" if index < 2 else "#fff7ec", "#b9d9ef" if index < 2 else "#efbd80")
        drawing.text(46, y + 39, title, 28, ORANGE if index == 2 else NAVY, True)
        drawing.text(314, y + 39, detail, 25)
    drawing.text(44, 2340, "事实来源及可维护内容：docs/architecture/source-map.json；企业验收与未完成目标：docs/remaining-task-ledger.md", 21, "#607a9a")
    drawing.save(output / "agentflow-panorama.svg")


def business_flow(spec, output):
    """以专用报销链路示例展示主路径、未知恢复、零应付及模型辅助侧路。"""
    d = Drawing(3072, 3100, "AgentFlow 端到端业务流程与执行边界", spec)
    d.text(1536, 76, "AgentFlow 端到端业务流程与执行边界", 58, NAVY, True, "middle")
    d.text(1536, 127, "以专用报销链路为例 · 实际签收、会签与复核要求由已发布流程及法人配置决定", 28, anchor="middle")
    d.text(3048, 28, f'源码快照 {spec["snapshotDate"]} · {spec["baselineCommit"][:7]}', 19, "#627c9e", anchor="end")
    d.rect(24, 156, 3024, 72, "#edf6ff", "#b9d9ef")
    d.text(50, 201, "全程守卫：可信主体 / 租户与字段权限 / 预期版本 / 固定轮次 / 幂等原请求 / 审计与证据有效期", 29, bold=True)

    def lane(y, height, number, title, color, note):
        d.rect(24, y, 3024, height, "#ffffff", color)
        d.rect(24, y, 3024, 51, color, color)
        d.text(45, y + 35, f"{number}  {title}", 29, "#ffffff", True)
        d.text(3025, y + 34, note, 22, "#ffffff", anchor="end")

    lane(250, 277, "01", "设计与发布", "#2977a7", "发布版本、表单、审批规则与引擎部署共同提交")
    design = [
        ("模板复制 / 创建草稿", ["5 个内置模板或独立设计", "复制保留模板来源"]),
        ("配置表单与流程图", ["节点 / 条件 / 选人 / 字段权限", "自动保存与 revision 冲突保护"]),
        ("校验与发布就绪检查", ["白名单 AST / 图结构 / 身份引用", "固定日历、事件及子流程依据"]),
        ("模拟与版本核对", ["分支覆盖、样例路径、版本差异", "模拟不启动真实流程实例"]),
        ("发布不可变版本", ["业务版本 + 发布者与变更说明", "生成 BPMN 并绑定引擎标识"]),
    ]
    for i, (title, lines) in enumerate(design):
        x = 65 + i * 606
        d.box(x, 327, 515, 166, title, lines)
        if i < 4:
            d.arrow([(x + 519, 411), (x + 603, 411)])

    lane(550, 412, "02", "申请填报、预检与可信提交", "#1588ad", "远端预检在事务外；正式提交只使用仍有效的已确认事实")
    d.box(65, 624, 375, 189, "专用草稿与原件", ["发票、费用行、精确分摊", "任职、法人、账户与类别", "草稿可反复保存与补正"])
    d.box(500, 624, 390, 189, "持久财务预检", ["目录 / 汇率 / 制度 / 验票", "预算预检、资源与账户校验", "记录输入双版本与有效期"])
    d.diamond(950, 623, 285, 189, ["最新预检", "READY 且有效?"])
    d.box(1295, 624, 555, 189, "本地提交短事务", ["锁内重读双版本及证据", "冻结轮次金额与资源预留", "启动审批 + 保存预算原命令"])
    d.box(1910, 624, 425, 189, "提交回执与真实待办", ["响应与全部本地变更原子保存", "预算操作只表示已登记", "业务审批按发布流程推进"])
    d.box(2395, 624, 605, 189, "后台预算冻结与原操作查询", ["领取 → 事务外预算 HTTP → 结果落库", "不足：退回当前轮次；未知：查原操作", "财务关键阶段以真实预算确认作守卫"])
    for x1, x2 in [(440, 500), (890, 950), (1235, 1295), (1850, 1910), (2335, 2395)]:
        d.arrow([(x1 + 3, 718), (x2 - 3, 718)])
    d.text(1260, 697, "通过", 20, GREEN, True, "middle")
    d.box(500, 850, 735, 83, "补正 / 重新预检", ["证据失效、配置或输入版本变化时，重新取证"], ORANGE, "#fff8ed")
    d.arrow([(1092, 817), (1092, 845)], ORANGE, label="不通过", label_at=(1150, 839))
    d.arrow([(497, 892), (252, 892), (252, 819)], ORANGE)
    d.text(1485, 888, "排队成功 ≠ 外部预算已冻结", 28, ORANGE, True)

    lane(984, 470, "03", "人工审批与业务财务节点", PURPLE, "任务期限按固定日历；转交与委派不重置已开始的期限")
    d.box(65, 1105, 355, 202, "固定任职与动态选人", ["从轮次任职解析主管或字段", "节点激活固定会签名单", "建立真实可办理的待办"], PURPLE, "#f6f2ff")
    d.box(465, 1105, 360, 202, "业务人工审批", ["全员 / 任一 / 比例会签", "领取、转交、委派与代理", "真实批准意见分别留痕"], PURPLE, "#f6f2ff")
    d.box(870, 1105, 360, 202, "纸件签收与业务核对", ["按法人配置决定是否需要", "核对原申请与财务版本", "确认签收不会直接付款"], PURPLE, "#f6f2ff")
    d.box(1275, 1105, 470, 202, "财务审核 / 核减 / 复核", ["职责分离与敏感字段权限", "真实预算、票据与金额守卫", "核减按专用用例留存原依据"], PURPLE, "#f6f2ff")
    d.diamond(1800, 1110, 290, 192, ["人工审批", "结论分支"])
    d.box(2150, 1105, 850, 202, "批准后登记凭证准备", ["最终批准与准备意图同事务成功或回滚", "保存批准版本、财务轮次、金额来源和原目标", "此刻只有准备任务，不表示 ERP 已过账"], GREEN, "#effaf4")
    for x1, x2 in [(420, 465), (825, 870), (1230, 1275), (1745, 1800), (2090, 2150)]:
        d.arrow([(x1 + 3, 1206), (x2 - 3, 1206)], PURPLE)
    d.text(2120, 1184, "最终批准", 19, GREEN, True, "middle")
    d.arrow([(2122, 816), (2122, 1055), (242, 1055), (242, 1099)], BLUE)
    d.arrow([(2700, 816), (2700, 1024), (1505, 1024), (1505, 1099)], GREEN, label="已确认预算事实", label_at=(2520, 1048))
    d.box(820, 1350, 1270, 78, "退回 / 撤回 / 驳回 / 作废按当前业务状态处理", ["可编辑状态补正后建立新轮次；旧轮次、原件和已发生资金事实保留"], ORANGE, "#fff8ed")
    d.arrow([(1945, 1308), (1945, 1345)], ORANGE, label="其他处置", label_at=(2010, 1334))
    d.text(85, 1404, "审批人也可显式使用下方 Agent 辅助侧路", 22, PURPLE)

    lane(1476, 537, "04", "独立会计执行、财务授权与出纳付款", GREEN, "持久命令与回执负责资金事实；Flowable 只负责审批推进")
    finance = [
        ("准备期间与科目", ["领取固定批准来源", "事务外读 ERP 期间与映射"]),
        ("复核并登记挂账", ["同锁复核已发布科目选择", "保存唯一原凭证命令"]),
        ("原凭证过账与查询", ["事务外执行 ERP HTTP", "真实 POSTED 后才可授权"]),
        ("财务显式短期授权", ["绑定批准、凭证与原收款人", "授权窗口与双版本约束"]),
        ("独立出纳选择与复查", ["出纳与财务及收款人分离", "复核账户后登记原付款"]),
    ]
    for i, (title, lines) in enumerate(finance):
        x = 65 + i * 606
        d.box(x, 1558, 515, 165, title, lines, GREEN, "#effaf4")
        if i < 4:
            d.arrow([(x + 520, 1640), (x + 600, 1640)], GREEN)
    d.arrow([(2575, 1312), (2575, 1462), (47, 1462), (47, 1640), (60, 1640)], GREEN)
    d.box(2310, 1780, 685, 190, "事务外资金发送 / 原交易查询", ["领取与 SENDING 保存后发送原命令", "连接中断或租约异常先查原交易", "原目标、编号、金额、账户与来源保持"], GREEN, "#effaf4")
    d.diamond(1825, 1770, 395, 210, ["权威资金结果", "明确且无争议?"])
    d.box(1220, 1780, 505, 190, "记录真实到账与原回执", ["本地保存原成功修订", "登记结算及付款凭证意图", "本地失败恢复原查询，不再次首发"], GREEN, "#effaf4")
    d.box(65, 1780, 1045, 190, "失败 / 冲突 / 退票 / 未知的独立恢复", ["未知先查询；权威查无后才允许明确确认的原号重发", "明确失败、安全结束、冲回、退票与争议分别处理", "不得换新编号绕过原操作；已到账事实不可被抹去"], ORANGE, "#fff8ed")
    d.arrow([(2747, 1729), (2747, 1774)], GREEN)
    d.arrow([(2305, 1875), (2227, 1875)], GREEN)
    d.arrow([(1818, 1875), (1731, 1875)], GREEN, label="成功", label_at=(1775, 1854))
    d.arrow([(2022, 1986), (2022, 1996), (589, 1996), (589, 1976)], ORANGE)
    d.arrow([(588, 1775), (588, 1744), (2600, 1744), (2600, 1774)], ORANGE,
            label="原查询；权威查无后才可明确恢复原号", label_at=(1520, 1752))

    lane(2035, 557, "05", "核销、独立付款凭证与归档", "#2977a7", "不同账本分别确认；归档核对必要条件，历史依据持续保留")
    d.box(65, 2195, 480, 195, "真实资金依据", ["银行成功回执 / 全额借款冲销", "零额核定有独立来源", "零应付不制造零额银行付款"], GREEN, "#effaf4")
    d.box(725, 2135, 760, 195, "费用核销与预算实际消费", ["同事务消费发票、事前额度及借款预留", "登记预算 CONSUME；真实确认后 SETTLED", "重试保持已消费标记，争议不恢复旧资源"], GREEN, "#effaf4")
    d.box(725, 2370, 760, 195, "独立付款凭证准备与 ERP 过账", ["仅实际银行付款才登记 PAYMENT 凭证", "沿原成功修订、期间与科目映射准备", "未过账不否认银行已到账"], GREEN, "#effaf4")
    d.box(1695, 2252, 645, 212, "归档条件与原件复核", ["锁内读取申请、轮次与必要财务终态", "事务外读取全部原件并校验摘要", "再锁内确认依据未变化，封存不可变清单"], BLUE, "#f0f9ff")
    d.box(2490, 2252, 505, 212, "归档与运营反馈", ["受字段权限约束的 ZIP 下载", "消息、审计、统计与指标", "后续争议保留原清单与历史"], BLUE, "#f0f9ff")
    d.arrow([(1472, 1976), (1472, 2023), (305, 2023), (305, 2189)], GREEN)
    d.arrow([(550, 2290), (630, 2290), (630, 2232), (720, 2232)], GREEN)
    d.arrow([(630, 2290), (630, 2468), (720, 2468)], GREEN, label="实际付款", label_at=(657, 2420))
    d.arrow([(1491, 2232), (1590, 2232), (1590, 2295), (1689, 2295)], GREEN)
    d.arrow([(1491, 2468), (1590, 2468), (1590, 2420), (1689, 2420)], GREEN)
    d.arrow([(2346, 2358), (2484, 2358)], BLUE)
    d.text(2490, 2530, "归档 ≠ 将外部未知结果改为成功", 25, ORANGE, True)

    lane(2614, 321, "06", "可选 Agent 辅助侧路", PURPLE, "建议的生成、采纳与最终审批是分别授权的动作")
    assistant = [
        ("选择当前可读输入", ["字段与附件来源明确授权", "确认模型目标指纹"]),
        ("持久运行排队", ["绑定任务、版本及轮次", "保存所选来源与原文依据"]),
        ("领取与事务外模型", ["专用线程 / 持久租约", "超时不盲目重复远端调用"]),
        ("结果校验与留存", ["严格 JSON 与来源引用", "复核当前权限、版本及期限"]),
        ("人工修订与处置", ["当前有效审批人采纳或拒绝", "保存模型原文及复核历史"]),
        ("独立办理审批", ["建议不能代替批准", "金额和财务事实走专用用例"]),
    ]
    for i, (title, lines) in enumerate(assistant):
        x = 65 + i * 498
        d.box(x, 2702, 460, 186, title, lines, PURPLE, "#f6f2ff", True)
        if i < 5:
            d.arrow([(x + 465, 2795), (x + 493, 2795)], PURPLE, True)
    d.rect(24, 2957, 3024, 84, "#fff8ed", "#ecc595")
    d.text(50, 2991, "图例：蓝色为本地用例与审批协作；绿色为权威事实确认；橙色为不通过、未知与人工恢复；紫色虚线为可选 Agent。", 24)
    d.text(50, 3027, "本图是源码中已有专用报销链路的结构说明；企业模型、预算、ERP、银行等实际效果仍以目标环境验收为准。", 24)
    d.text(44, 3079, "维护依据：source-map.json 与 README 中的 Mermaid；原命令、版本、资金状态和审计规则详见对应领域文档。", 21, "#607a9a")
    d.save(output / "agentflow-business-flow.svg")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/assets")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    spec = json.loads((ROOT / "docs/architecture/source-map.json").read_text(encoding="utf-8"))
    panorama(spec, args.output)
    business_flow(spec, args.output)
    print(json.dumps({"result": "PASS", "output": str(args.output), "diagrams": 2}, ensure_ascii=False))


if __name__ == "__main__":
    main()
