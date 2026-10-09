#!/usr/bin/env python3
"""本地合成 HTTP 验收：办理恢复、原键补正、强退未知结果及本人隔离。"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("handling_runtime", ROOT / "scripts/check-precheck-explanation.py")
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def browser_check(runtime, directory, fixture, result):
    """挂载真实费用编辑器并访问本次 Java HTTP，不替换前端 API 函数。"""
    from playwright.sync_api import sync_playwright, expect
    web = ROOT / "agentflow-web"
    browser_dir = directory / "browser"
    browser_dir.mkdir()
    config = {"web": str(web), "root": str(browser_dir), "backend": f"http://127.0.0.1:{runtime.port}"}
    module = browser_dir / "server.mjs"
    module.write_text("""import { createRequire } from 'node:module';
import { writeFileSync } from 'node:fs';
const config = """ + json.dumps(config) + """;
const require = createRequire(config.web + '/package.json');
const { createServer } = await import(require.resolve('vite'));
const { default: vue } = await import(require.resolve('@vitejs/plugin-vue'));
const server = await createServer({configFile:false,root:config.root,plugins:[vue()],resolve:{alias:{vue:require.resolve('vue/dist/vue.runtime.esm-bundler.js')},dedupe:['vue']},
  server:{host:'127.0.0.1',port:0,fs:{allow:[config.root,config.web]},proxy:{'/api':{target:config.backend,changeOrigin:false}}}});
await server.listen(); writeFileSync(config.root + '/port',String(server.httpServer.address().port));
""")
    (browser_dir / "index.html").write_text('<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>本地合成报销验收</title><div id="app"></div><script type="module" src="/fixture.js"></script></html>')
    prefix = "/@fs" + str(web)
    (browser_dir / "fixture.js").write_text("import { createApp, h } from 'vue';\n"
        + "import Editor from '" + prefix + "/src/components/ExpenseEditor.vue';\n"
        + "import { api, bindAuthenticationActor } from '" + prefix + "/src/api.ts';\n"
        + "import '" + prefix + "/src/styles.css';\n"
        + "localStorage.setItem('agentflow.token'," + json.dumps(runtime.tokens["alice"]) + ");\n"
        + "bindAuthenticationActor({tenantId:'demo',userId:'alice',roles:['EMPLOYEE']});\n"
        + "const report=await api.expenseReport(" + json.dumps(fixture["report"]["id"]) + ");\n"
        + "createApp({render:()=>h(Editor,{initial:report,scopeKey:'demo:alice'})}).mount('#app');\n"
        + "document.body.style.cssText='margin:0;padding:16px;background:#f7f9f8';document.querySelector('#app').style.cssText='max-width:1100px;margin:auto';\n")
    with (browser_dir / "vite.log").open("w") as log:
        process = subprocess.Popen(["node", str(module)], cwd=web, stdout=log, stderr=subprocess.STDOUT)
        try:
            until = time.monotonic() + 20
            while not (browser_dir / "port").exists():
                assert process.poll() is None and time.monotonic() < until, "Browser fixture server unavailable"
                time.sleep(0.1)
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel="chrome", headless=True)
                page = browser.new_page(viewport={"width": 1360, "height": 1000})
                errors = []; page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto("http://127.0.0.1:" + (browser_dir / "port").read_text(), wait_until="networkidle")
                panel = page.get_by_role("region", name="报销办理助手")
                expect(panel).to_be_visible()
                page.screenshot(path=str(browser_dir / "handling-loaded.png"), full_page=True)
                (browser_dir / "loaded-text.txt").write_text(page.locator("body").inner_text())
                panel.get_by_text("核对已保存的费用与依据", exact=True).click()
                panel.get_by_role("button", name="读取当前费用", exact=True).click()
                expect(panel.get_by_role("status").filter(has_text="已读取")).to_be_visible()
                panel.get_by_label("费用行", exact=True).select_option("1")
                panel.get_by_role("button", name="查询适用制度", exact=True).click()
                expect(panel.get_by_role("status").filter(has_text="合成办公费制度")).to_be_visible()
                panel.get_by_role("button", name="查看全部", exact=False).click()
                original_steps = panel.locator("ol.steps > li").all_text_contents()
                count = len(original_steps)
                page.reload(wait_until="networkidle")
                panel.get_by_role("button", name="查看全部", exact=False).click()
                expect(panel.locator("ol.steps > li")).to_have_count(count)
                assert panel.locator("ol.steps > li").all_text_contents() == original_steps, "Restored step evidence changed"
                panel.get_by_role("button", name="只看最近 4 步", exact=True).click()
                panel.get_by_text("本人助手执行与用量", exact=True).click()
                expect(panel.get_by_text("用量未知", exact=False).first).to_be_visible()
                expect(panel.get_by_text("最终结果尚未记录", exact=False)).to_be_visible()
                panel.screenshot(path=str(browser_dir / "handling-panel.png"))
                page.screenshot(path=str(browser_dir / "handling-desktop.png"), full_page=True)
                page.get_by_label("报销标题", exact=True).fill("尚未保存的人工修改")
                panel.get_by_text("核对已保存的费用与依据", exact=True).click()
                expect(panel.get_by_role("button", name="读取当前费用", exact=True)).to_be_disabled()
                page.set_viewport_size({"width": 390, "height": 844})
                page.evaluate("window.scrollTo(0, 0)")
                page.screenshot(path=str(browser_dir / "handling-mobile-viewport.png"))
                page.screenshot(path=str(browser_dir / "handling-mobile.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Mobile overflow"
                assert not errors, errors
                browser.close()
                result["browser"] = {"result": "PASS", "backend": "REAL_LOCAL_HTTP", "businessData": "SYNTHETIC", "stepsAfterReload": count, "pageErrors": errors, "desktop": "1360x1000", "mobile": "390x844"}
        finally:
            process.terminate(); process.wait(timeout=15)


def run(java, jar, directory, result, browser=False):
    """只操作本次目录与进程，所有外部身份、财务和模型均为回环合成服务。"""
    sources = common.Sources(directory)
    runtime = common.Runtime(java, directory, sources)
    try:
        runtime.start(jar, worker=False)
        catalog = runtime.call("GET", "/process-templates", user="admin")
        template = next(item for item in catalog if item["key"] == "expense-report")
        fixture = common.setup(runtime, sources, template_version=template["templateVersion"])
        report = fixture["report"]; root = "/expense-reports/" + report["id"]; tasks = root + "/handling-tasks"
        start = {"applicationVersion": 1, "financialVersion": 1, "goal": "核对差旅资料并补正预检问题"}; key = str(uuid4())
        task = runtime.call("POST", tasks, start, expected=201, key=key)
        assert runtime.call("POST", tasks, start, expected=201, key=key) == task
        inspect = tasks + "/" + task["id"] + "/inspect"
        fact = runtime.call("POST", inspect, {"expectedVersion": task["version"], "tool": "EXPENSE"})
        invoice = runtime.call("POST", inspect, {"expectedVersion": fact["task"]["version"], "tool": "INVOICE", "referenceId": fixture["invoiceId"]})
        assert invoice["result"]["invoice"]["id"] == fixture["invoiceId"]
        for user in ["bob", "finance", "admin"]:
            runtime.call("GET", tasks, user=user, expected=404)
            runtime.call("POST", inspect, {"expectedVersion": invoice["task"]["version"], "tool": "EXPENSE"}, user=user, expected=404)
        check = common.precheck(runtime, fixture)
        path, body = common.generation(runtime, fixture, check)
        queued = runtime.call("POST", path, body, expected=202)
        assert not sources.model_calls
        runtime.stop(); runtime.start(jar, worker=True)
        detail = common.wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda value: value["status"] == "COMPLETED")
        history = runtime.call("GET", tasks)[0]
        assert history["id"] == task["id"] and len(history["steps"]) == 4 and len(sources.model_calls) == 1
        usage = runtime.call("GET", "/agent-executions/usage?subjectId=" + report["id"])
        assert len(usage) == 1 and usage[0]["runId"] == queued["id"] and usage[0]["usageStatus"] == "NOT_REPORTED" and usage[0]["totalTokens"] is None
        assert runtime.call("GET", "/agent-executions/usage?subjectId=" + report["id"], user="bob") == []
        result["queuedRestart"] = {"handlingId": task["id"], "runId": queued["id"], "modelCalls": 1, "steps": 4}
        correction = {"expectedRunVersion": detail["version"], "applicationVersion": 1, "financialVersion": 1,
                      "selectedIssueIds": [item["issueSourceId"] for item in detail["suggestion"]["items"]], "comment": "本人核对合成补正",
                      "content": {**report["content"], "title": "本人核对后的用途说明"}}
        correction_path = path + "/" + queued["id"] + "/correct"; correction_key = str(uuid4())
        runtime.lose_response(correction_path, correction, correction_key, 200)
        receipt = runtime.call("POST", correction_path, correction, key=correction_key)
        assert runtime.call("POST", correction_path, correction, key=correction_key) == receipt
        assert receipt["expense"]["financialVersion"] == 2 and receipt["expense"]["applicationStatus"] == "DRAFT"
        checked = common.wait_for(lambda: runtime.call("GET", root + "/prechecks/" + receipt["precheckId"]), lambda value: value["job"]["status"] == "BLOCKED")
        history = runtime.call("GET", tasks)[0]
        assert history["financialVersion"] == 2 and len(history["steps"]) == 7
        result["unknownCorrectionRecovery"] = {"precheckId": receipt["precheckId"], "financialVersion": 2, "applicationStatus": "DRAFT", "steps": 7}
        # 在真实模型 HTTP 已到达且未返回的窗口强退，重启只结算原租约。
        sources.hold("HOLD")
        path, body = common.generation(runtime, fixture, checked)
        interrupted = runtime.call("POST", path, body, expected=202)
        assert sources.entered.wait(10)
        runtime.stop(force=True); sources.release.set()
        assert sources.returned.wait(5)
        count = len(sources.model_calls)
        runtime.start(jar, worker=True)
        failed = common.wait_for(lambda: runtime.call("GET", path + "/" + interrupted["id"]), lambda value: value["status"] == "FAILED", 60)
        assert failed["failure"] == "MODEL_TIMEOUT" and len(sources.model_calls) == count
        unknown = next(item for item in runtime.call("GET", "/agent-executions/usage?subjectId=" + report["id"]) if item["runId"] == interrupted["id"])
        assert unknown["outcome"] == "IN_PROGRESS" and unknown["completedAt"] is None and unknown["totalTokens"] is None
        restored = runtime.call("GET", tasks)[0]
        assert restored["id"] == task["id"] and restored["steps"][-1]["referenceId"] == interrupted["id"]
        result["interruptedRestart"] = {"runId": interrupted["id"], "businessFailure": failed["failure"], "observation": unknown["outcome"], "additionalModelCalls": 0}
        if browser:
            browser_check(runtime, directory, fixture, result)
        assert not sources.errors, sources.errors
        result["result"] = "PASS"
        result["scope"] = "LOCAL_SYNTHETIC_HTTP_NOT_ENTERPRISE_ACCEPTANCE"
        result["jarSha256"] = common.digest(jar)
    finally:
        runtime.stop(); sources.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--browser", action="store_true", help="使用本地 Playwright 与 Chrome 验证实际费用编辑器")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=False)
    result = {"result": "INCOMPLETE"}
    try:
        run(args.java, args.jar.resolve(), args.output_dir.resolve(), result, args.browser)
    finally:
        common.save(args.output_dir / "result.json", result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
