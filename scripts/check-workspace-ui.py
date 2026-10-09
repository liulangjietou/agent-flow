#!/usr/bin/env python3
"""在隔离的本地演示环境检查桌面工作区布局、按钮与键盘操作，不提交业务数据。

依赖 Python Playwright 与本机 Chrome。先启动独立的演示后端和前端，再执行：
python scripts/check-workspace-ui.py --url http://127.0.0.1:5186 --output /fyoung/tmp/agentflow-ui-check
"""
import argparse
import json
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


def settle(page):
    """等待 Vue 更新发起请求后再等待网络空闲，避免截到上一个页面。"""
    page.wait_for_timeout(150)
    page.wait_for_load_state("networkidle")


def select_page(page, label):
    """通过用户可见的侧栏菜单进入桌面页面。"""
    page.get_by_role("navigation", name="工作空间页面").get_by_role("button", name=label, exact=True).click()
    settle(page)
    page.evaluate("window.scrollTo(0, 0)")


def run(args):
    """输出截图和可复核的尺寸报告，任何检查失败都返回非零退出码。"""
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {"pages": [], "errors": [], "failures": []}
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel, headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000}, device_scale_factor=1)
        page.on("pageerror", lambda error: report["errors"].append(str(error)))
        page.goto(args.url, wait_until="networkidle")
        expect(page.get_by_label("用户名", exact=True)).to_be_visible()
        page.screenshot(path=str(output / "login.png"), full_page=True)
        page.get_by_label("租户空间").fill("demo")
        page.get_by_label("用户名", exact=True).fill(args.user)
        page.get_by_label("密码", exact=True).fill("demo")
        page.get_by_role("button", name="进入工作台", exact=False).click()
        expect(page.locator("main")).to_be_visible()
        settle(page)
        dismiss = page.get_by_role("button", name="关闭提示", exact=True)
        if dismiss.count():
            dismiss.click()
        navigation = page.get_by_role("navigation", name="工作空间页面")
        labels = navigation.get_by_role("button").evaluate_all("items => items.map(item => item.getAttribute('aria-label'))")
        for width, height in [(1920, 1080), (1440, 1000), (1280, 800), (1024, 900)]:
            page.set_viewport_size({"width": width, "height": height})
            for index, label in enumerate(labels):
                select_page(page, label)
                result = page.evaluate("""() => ({
                  overflow: document.documentElement.scrollWidth - innerWidth,
                  smallButtons: [...document.querySelectorAll('main button')]
                    .filter(e => e.getClientRects().length && !e.closest('.workspace-navigation') && e.getBoundingClientRect().height < 35)
                    .map(e => ({label: e.innerText.trim().slice(0, 40), height: e.getBoundingClientRect().height})),
                  navClipping: [...document.querySelectorAll('.workspace-navigation nav button[aria-current] span')]
                    .filter(e => e.getClientRects().length && e.getBoundingClientRect().right > e.closest('button').getBoundingClientRect().right).length
                })""")
                result.update(page=label, width=width)
                report["pages"].append(result)
                if result["overflow"] > 1 or result["smallButtons"] or result["navClipping"]:
                    report["failures"].append(result)
                page.screenshot(path=str(output / f"page-{width}-{index:02d}.png"))

        # 使用原生语义元素探针验证共享样式的边界，局部标题和导航不应继承应用外壳布局。
        boundary = page.evaluate("""() => {
          const host = document.createElement('section');
          host.style.width = '300px';
          host.innerHTML = '<header>业务区标题</header><nav><button>局部操作</button></nav><textarea aria-label="焦点探针"></textarea>';
          document.querySelector('main').append(host);
          const header = host.querySelector('header');
          const result = {headerHeight: header.getBoundingClientRect().height, navWidth: host.querySelector('button').getBoundingClientRect().width};
          host.remove();
          return result;
        }""")
        report["styleBoundary"] = boundary
        if boundary["headerHeight"] >= 50 or boundary["navWidth"] >= 300:
            report["failures"].append({"styleBoundary": boundary})

        # 桌面键盘用户可越过长导航直接进入工作区；操作按钮保留可见焦点。
        page.locator(".skip-link").focus()
        page.keyboard.press("Enter")
        expect(page.locator("main")).to_be_focused()
        refresh = page.get_by_role("button", name="刷新数据", exact=True)
        refresh.focus()
        focus = refresh.evaluate("e => ({style: getComputedStyle(e).outlineStyle, width: getComputedStyle(e).outlineWidth})")
        if focus["style"] == "none" or float(focus["width"].removesuffix("px")) < 2:
            report["failures"].append({"keyboardFocus": focus})
        page.emulate_media(reduced_motion="reduce")
        if refresh.evaluate("e => getComputedStyle(e).transitionDuration") != "0s":
            report["failures"].append({"reducedMotion": "Unexpected transition"})
        browser.close()
    report["result"] = "PASS" if not report["errors"] and not report["failures"] else "FAIL"
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({"result": report["result"], "pages": len(report["pages"]), "failures": len(report["failures"]), "errors": report["errors"], "output": str(output)}, ensure_ascii=False))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="本次独立启动的本地前端 URL")
    parser.add_argument("--output", required=True, type=Path, help="截图和报告目录，使用 /fyoung/tmp 下的独立目录")
    parser.add_argument("--user", default="admin", choices=["admin", "employee", "alice", "finance", "cashier"])
    parser.add_argument("--channel", default="chrome", help="浏览器通道，默认使用本机 Chrome")
    options = parser.parse_args()
    if urlparse(options.url).hostname not in ("127.0.0.1", "localhost"):
        parser.error("Only an isolated local demo server is supported")
    raise SystemExit(run(options))
