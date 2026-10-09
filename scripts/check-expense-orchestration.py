#!/usr/bin/env python3
"""四项优化的真实 HTTP 与浏览器验收；仅使用独立数据库和回环合成服务。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("orchestration_runtime", ROOT / "scripts/check-precheck-explanation.py")
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


class Sources(common.Sources):
    """模型只返回已知动作和差异，失败查询用于检验原步骤恢复。"""
    agent_mode = "READ"
    fail_policy = False

    def model_result(self, content, mode):
        if "goal" in content:
            action = "EXTRACT_INVOICE" if self.agent_mode == "INVOICE" else "FINISH" if content["answers"] else "ASK_USER" if content["history"] else "EXPENSE"
            decision = {"action": action, "referenceId": content["scope"]["invoiceIds"][0] if action == "EXTRACT_INVOICE" else None,
                        "lineNo": None, "message": "请补充费用用途。" if action == "ASK_USER" else "按授权范围核对本单材料。"}
            return {"model": "synthetic-agent", "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps(decision, ensure_ascii=False)}}]}
        result = super().model_result(content, mode)
        output = json.loads(result["choices"][0]["message"]["content"])
        fields = [source for source in content["sources"] if source["reference"]["sourceId"].startswith("expense:field[")]
        for item in output["items"][:1]:
            item["patches"] = [{"lineNo": 1, "field": source["reference"]["sourceId"].split(".")[-1], "beforeValue": source["content"],
                "afterValue": "本人确认的客户培训用途", "impact": "补充用途后重新执行费用预检"} for source in fields]
            item["evidence"].extend(source["reference"] for source in fields)
        result["choices"][0]["message"]["content"] = json.dumps(output, ensure_ascii=False)
        return result

    def finance(self, operation, request):
        if operation == "expense-policy-guidance" and self.fail_policy:
            return {"synthetic": "interrupted read"}, 503
        return super().finance(operation, request)


def browser_check(runtime, directory, fixture, result, task, explanation):
    """挂载实际编辑器，确认自动循环、刷新、提问和逐项补正都使用真实 API。"""
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
await server.listen();writeFileSync(config.root+'/port',String(server.httpServer.address().port));
""")
    (browser_dir / "index.html").write_text('<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>自动报销办理验收</title><div id="app"></div><script type="module" src="/fixture.js"></script></html>')
    prefix = "/@fs" + str(web)
    (browser_dir / "fixture.js").write_text("import {createApp,h} from 'vue';\n"
        + "import Editor from '" + prefix + "/src/components/ExpenseEditor.vue';\n"
        + "import {api,bindAuthenticationActor} from '" + prefix + "/src/api.ts';\n"
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
                assert process.poll() is None and time.monotonic() < until
                time.sleep(.1)
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel="chrome", headless=True)
                page = browser.new_page(viewport={"width": 1360, "height": 1000})
                page.set_default_timeout(20000)
                errors = []; page.on("pageerror", lambda error: errors.append(str(error)))
                try:
                    page.goto("http://127.0.0.1:" + (browser_dir / "port").read_text(), wait_until="networkidle")
                    agent = page.get_by_role("region", name="受控自动办理", exact=True)
                    agent.get_by_role("button", name="核对发送内容与目的地", exact=True).click()
                    agent.get_by_label("我已核对，授权上述范围内的自动查询、结果发送与下一步判断", exact=True).check()
                    agent.get_by_role("button", name="确认授权并开始", exact=True).click()
                    expect(agent.get_by_label("补充资料", exact=True)).to_be_visible()
                    root = "/expense-reports/" + fixture["report"]["id"] + "/handling-tasks/" + task["id"] + "/agent"
                    original = runtime.call("GET", root)
                    page.screenshot(path=str(browser_dir / "agent-question-desktop.png"), full_page=True)
                    page.reload(wait_until="networkidle")
                    expect(agent.get_by_label("补充资料", exact=True)).to_be_visible()
                    assert runtime.call("GET", root)["id"] == original["id"]
                    agent.get_by_label("补充资料", exact=True).fill("本次客户培训使用的办公材料")
                    agent.get_by_role("button", name="继续原办理", exact=True).click()
                    common.wait_for(lambda: runtime.call("GET", root), lambda v: v["state"]["status"] == "COMPLETED")
                    panel = page.get_by_role("region", name="预检解释与补正建议", exact=True)
                    panel.get_by_role("button", name="刷新记录", exact=True).click()
                    panel.locator('.explanation-list button').first.click()
                    panel.get_by_label("采纳这条解释", exact=True).first.check()
                    panel.get_by_role("button", name="按所选建议补正", exact=True).click()
                    page.get_by_label("采纳第 1 行 · 费用说明", exact=True).check()
                    expect(page.get_by_text("本人确认的客户培训用途", exact=True).first).to_be_visible()
                    page.screenshot(path=str(browser_dir / "structured-difference-desktop.png"), full_page=True)
                    page.locator(".correction-checklist").screenshot(path=str(browser_dir / "difference-desktop-detail.png"))
                    page.set_viewport_size({"width": 390, "height": 844})
                    page.screenshot(path=str(browser_dir / "structured-difference-mobile.png"), full_page=True)
                    page.locator(".correction-checklist").screenshot(path=str(browser_dir / "difference-mobile-detail.png"))
                    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "Mobile overflow"
                    page.get_by_role("button", name="应用所选差异并重新预检", exact=True).click()
                    saved = common.wait_for(lambda: runtime.call("GET", "/expense-reports/" + fixture["report"]["id"]), lambda v: v["financialVersion"] == 2)
                    assert saved["content"]["lines"][0]["description"] == "本人确认的客户培训用途"
                    assert saved["content"]["lines"][0]["claimedGross"] == fixture["report"]["content"]["lines"][0]["claimedGross"]
                    assert not saved["content"]["lines"][0].get("exceptionReason")
                    # 新的一次办理绑定真实 XML 任务，票面人工确认后进入原费用草稿表单。
                    expense_root = "/expense-reports/" + fixture["report"]["id"]
                    corrected = runtime.call("GET", expense_root + "/precheck-explanations/" + explanation["id"])
                    common.wait_for(lambda: runtime.call("GET", expense_root + "/prechecks/" + corrected["correction"]["precheckId"]), lambda v: v["job"]["status"] not in ("QUEUED", "RUNNING"))
                    tasks = expense_root + "/handling-tasks"; old = runtime.call("GET", tasks)[0]
                    runtime.call("POST", tasks + "/" + old["id"] + "/close", {"expectedVersion": old["version"]})
                    xml = b"<EInvoice><Header><Version>0.31</Version></Header><TaxSupervisionInfo><InvoiceNumber>000077</InvoiceNumber></TaxSupervisionInfo></EInvoice>"
                    invoice = runtime.call("POST", "/invoices", {"filename": "orchestration.xml", "size": len(xml), "sha256": hashlib.sha256(xml).hexdigest(), "format": "XML"}, expected=201)
                    runtime.call("PUT", "/invoices/" + invoice["id"] + "/content", raw=xml)
                    next_task = runtime.call("POST", tasks, {"applicationVersion": 2, "financialVersion": 2, "goal": "整理票面并继续费用草稿"}, expected=201)
                    runtime.sources.agent_mode = "INVOICE"
                    page.set_viewport_size({"width": 1360, "height": 1000}); page.reload(wait_until="networkidle")
                    agent.get_by_label("允许读取及安排整理票据：orchestration.xml", exact=False).check()
                    agent.get_by_role("button", name="核对发送内容与目的地", exact=True).click()
                    agent.get_by_label("我已核对，授权上述范围内的自动查询、结果发送与下一步判断", exact=True).check()
                    agent.get_by_role("button", name="确认授权并开始", exact=True).click()
                    agent.get_by_role("button", name="提取本地 XML 字段", exact=True).click()
                    next_root = tasks + "/" + next_task["id"] + "/agent"
                    bound = common.wait_for(lambda: runtime.call("GET", next_root), lambda v: bool(v["state"].get("childId")))
                    extraction_root = "/invoices/" + invoice["id"] + "/extraction-runs/" + bound["state"]["childId"]
                    common.wait_for(lambda: runtime.call("GET", extraction_root), lambda v: v["status"] == "COMPLETED")
                    agent.get_by_role("button", name="刷新本条结果", exact=True).click()
                    agent.get_by_label("确认发票号码", exact=True).check()
                    agent.get_by_role("button", name="保存勾选字段的确认值", exact=True).click()
                    agent.get_by_role("button", name="继续整理费用草稿并核对发送范围", exact=True).click()
                    expect(page.get_by_label("填报要求", exact=True)).to_have_value(__import__('re').compile("000077"))
                    agent.screenshot(path=str(browser_dir / "invoice-continuation.png"))
                    assert runtime.call("GET", "/invoices/" + invoice["id"])["verification"] == "PENDING"
                    result["invoiceBrowser"] = {"result": "PASS", "handlingId": next_task["id"], "extractionId": bound["state"]["childId"], "confirmedValueInOriginalDraft": "000077"}
                    assert not errors, errors
                    result["browser"] = {"result": "PASS", "agentId": original["id"], "originalStepIds": [s["id"] for s in original["state"]["steps"]],
                        "selectedPatchIds": ["expense:field[1].DESCRIPTION"], "financialVersion": 2, "pageErrors": errors, "desktop": "1360x1000", "mobile": "390x844"}
                finally:
                    page.screenshot(path=str(browser_dir / "last-page.png"), full_page=True)
                    (browser_dir / "last-page.txt").write_text(page.locator("body").inner_text())
                    browser.close()
        finally:
            process.terminate(); process.wait(timeout=15)


def run(java, jar, directory, result):
    sources = Sources(directory); runtime = common.Runtime(java, directory, sources)
    runtime.settings.update({"agentflow.expense-agent.worker-enabled": True, "agentflow.expense-agent.poll-delay-ms": 200, "agentflow.invoices.extraction-worker-enabled": True, "agentflow.invoices.extraction-poll-delay-ms": 200})
    try:
        runtime.start(jar, worker=True)
        template = next(v for v in runtime.call("GET", "/process-templates", user="admin") if v["key"] == "expense-report")
        fixture = common.setup(runtime, sources, template_version=template["templateVersion"])
        report = fixture["report"]; root = "/expense-reports/" + report["id"]; tasks = root + "/handling-tasks"
        task = runtime.call("POST", tasks, {"applicationVersion": 1, "financialVersion": 1, "goal": "核对报销材料并询问缺失用途"}, expected=201)
        check = common.precheck(runtime, fixture); path, body = common.generation(runtime, fixture, check)
        body["sourceIds"].extend(["expense:field[1].DESCRIPTION", "expense:field[1].EXCEPTION_REASON"])
        queued = runtime.call("POST", path, body, expected=202)
        explanation = common.wait_for(lambda: runtime.call("GET", path + "/" + queued["id"]), lambda v: v["status"] == "COMPLETED")
        assert len(explanation["suggestion"]["items"][0]["patches"]) == 2
        browser_check(runtime, directory, fixture, result, task, explanation)
        current = runtime.call("GET", tasks)[0]; history_root = tasks + "/" + current["id"]
        sources.fail_policy = True
        runtime.call("POST", history_root + "/inspect", {"expectedVersion": current["version"], "tool": "POLICY", "lineNo": 1}, expected=503)
        failed = next(v for v in runtime.call("GET", history_root + "/reads") if v["status"] == "FAILED")
        runtime.stop(); runtime.start(jar, worker=True); sources.fail_policy = False
        restored = next(v for v in runtime.call("GET", history_root + "/reads") if v["id"] == failed["id"])
        assert restored["inputDigest"] == failed["inputDigest"] and restored["input"] == failed["input"]
        recovery = history_root + "/reads/" + failed["id"] + "/resume"; key = str(uuid4()); resume = {"expectedVersion": restored["version"]}
        runtime.lose_response(recovery, resume, key, 200)
        count = len(sources.calls); receipt = runtime.call("POST", recovery, resume, key=key)
        assert receipt["task"]["id"] == current["id"] and len(sources.calls) == count
        assert next(v for v in runtime.call("GET", history_root + "/reads") if v["id"] == failed["id"])["status"] == "RECORDED"
        result["readRestart"] = {"originalReadId": failed["id"], "inputDigest": failed["inputDigest"], "extraCallsOnReceiptRecovery": 0}
        assert not sources.errors, sources.errors
        result.update(result="PASS", scope="LOCAL_SYNTHETIC_HTTP_NOT_ENTERPRISE_ACCEPTANCE", jarSha256=common.digest(jar))
    finally:
        runtime.stop(); sources.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True); parser.add_argument("--jar", type=Path, required=True); parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(); args.output_dir.mkdir(parents=True, exist_ok=False); result = {"result": "INCOMPLETE"}
    try: run(args.java, args.jar.resolve(), args.output_dir.resolve(), result)
    finally: common.save(args.output_dir / "result.json", result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
