#!/usr/bin/env python3
"""固定安装包的 H2 与合成接收方配套恢复验收；只创建独立目录和回环进程。"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import tempfile
import time
from uuid import uuid4
import zipfile


fixture = runpy.run_path(str(Path(__file__).with_name("check-service-task-restart.py")))
Receiver, Runtime = fixture["Receiver"], fixture["Runtime"]
save, publish_fixture = fixture["save"], fixture["publish_fixture"]


def digest(path):
    """文件摘要用于核对备份、隔离副本和源目录未变。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_content(path):
    """仅忽略单行独立约束的输出顺序，保留其正文、重复数量及其他全部内容。"""
    constraints, content = [], []
    for line in path.read_text().splitlines(keepends=True):
        if re.fullmatch(r'ALTER TABLE "[^"]+"\."[^"]+" ADD CONSTRAINT .+;\s*', line):
            constraints.append(line)
        else:
            content.append(line)
    return json.dumps([content, sorted(constraints)], ensure_ascii=False).encode()


def database_tool(java, h2, tool, database, sql, log, options=()):
    """全部应用写入停止后才离线导出或导入，不连接运行中的文件库。"""
    url = "jdbc:h2:file:" + str(database) + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE"
    with log.open("x") as output:
        subprocess.run([java, "-Djava.io.tmpdir=/fyoung/tmp", "-cp", str(h2), "org.h2.tools." + tool,
                        "-url", url, "-user", "sa", "-script", str(sql), *options],
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=60)


def submit(runtime, definition, title):
    """从公开接口发起申请，避免用 SQL 构造流程或服务状态。"""
    application = runtime.request("POST", "/applications", {
        "businessNo": "restore-" + str(uuid4()), "processKey": definition["key"], "definitionVersion": definition["version"],
        "title": title, "payload": {"reason": "synthetic-original", "secret": "synthetic-not-sent"}}, user="alice", expected=201)
    runtime.request("POST", "/applications/" + application["id"] + "/submit", {"expectedVersion": application["version"]}, user="alice")
    return application["id"]


def approve(runtime, identity):
    """恢复后仍通过真实人工待办批准，服务回执不能代替审批动作。"""
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        tasks = [task for task in runtime.request("GET", "/tasks", user="finance") if task["applicationId"] == identity]
        if tasks:
            break
        time.sleep(0.2)
    else:
        raise AssertionError("Original workflow did not reach human review")
    current = runtime.request("GET", "/applications/" + identity, user="alice")
    runtime.request("POST", "/tasks/" + tasks[0]["taskId"] + "/actions",
                    {"action": "APPROVE", "expectedVersion": current["version"], "comment": "核对配套恢复后的原操作"}, user="finance")
    approved = runtime.request("GET", "/applications/" + identity, user="alice")
    if approved["status"] != "APPROVED":
        raise AssertionError("Human approval did not complete the original application")
    return approved


def verify_pair(java, jar, directory, result):
    """冻结应用和接收方的共同恢复点，恢复到新文件库并只查询原执行号。"""
    source, backup, restored = (directory / name for name in ("source", "backup", "restored"))
    for path in (source, backup, restored):
        path.mkdir()
    source_receiver = Receiver(source)
    source_runtime = Runtime(java, jar, source, source_receiver)
    restored_runtime = restored_receiver = None
    try:
        source_runtime.start()
        definition = publish_fixture(source_runtime, source_receiver)
        source_receiver.release.set()
        completed_id = submit(source_runtime, definition, "备份前已批准")
        completed = approve(source_runtime, completed_id)
        save(source / "completed-application.json", completed)

        source_receiver.recorded.clear()
        source_receiver.release.clear()
        pending_id = submit(source_runtime, definition, "外部已执行但应用未收到结果")
        if not source_receiver.recorded.wait(15):
            raise AssertionError("Synthetic receiver did not persist the second effect")
        path = "/applications/" + pending_id + "/rounds/1/service-tasks"
        before = source_runtime.request("GET", path, user="alice")
        if len(before["items"]) != 1 or before["items"][0]["status"] != "EXECUTING":
            raise AssertionError("Backup fixture must hold an executing original operation")
        operation_id = before["items"][0]["id"]
        save(source / "pending-status.json", before)
        receiver_port, contract_digest = source_receiver.server.server_port, source_receiver.contract_digest
        source_runtime.stop(force=True)
        source_receiver.close()
        source_receiver = None

        with zipfile.ZipFile(jar) as archive:
            entry = next(name for name in archive.namelist() if name.startswith("BOOT-INF/lib/h2-") and name.endswith(".jar"))
            h2 = directory / "h2.jar"
            with h2.open("xb") as output:
                output.write(archive.read(entry))
        sql = backup / "database.sql"
        database_tool(java, h2, "Script", source / "data/agentflow", sql, directory / "backup.log")
        # 比较用导出排除部署设置和随机密码盐；业务、迁移及引擎表仍全部保留。
        inventory_options = ("-options", "NOPASSWORDS", "NOSETTINGS")
        source_sql = directory / "source-content.sql"
        database_tool(java, h2, "Script", source / "data/agentflow", source_sql, directory / "source-content.log", inventory_options)
        shutil.copy2(source / "receiver.json", backup / "receiver.json")
        shutil.copy2(source / "runtime.yaml", backup / "source-runtime.yaml")
        manifest = {"createdAt": datetime.now(timezone.utc).isoformat(), "synthetic": True, "jarSha256": digest(jar),
                    "receiverPort": receiver_port, "contractDigest": contract_digest, "pendingApplicationId": pending_id,
                    "operationId": operation_id, "completedApplicationId": completed_id,
                    "files": {p.name: digest(p) for p in backup.iterdir() if p.is_file()}}
        save(backup / "manifest.json", manifest)
        source_files = {str(p.relative_to(source)): digest(p) for p in source.rglob("*") if p.is_file()}
        for name, value in manifest["files"].items():
            if digest(backup / name) != value:
                raise AssertionError("Paired backup checksum changed before restoration")

        (restored / "data").mkdir()
        database_tool(java, h2, "RunScript", restored / "data/agentflow", sql, directory / "restore.log")
        restored_sql = directory / "restored-content.sql"
        database_tool(java, h2, "Script", restored / "data/agentflow", restored_sql, directory / "restored-content.log", inventory_options)
        if canonical_content(source_sql) != canonical_content(restored_sql):
            raise AssertionError("Restored table definitions, rows or sequences differ from the frozen source")
        shutil.copy2(backup / "receiver.json", restored / "receiver.json")
        original_journal = json.loads((backup / "receiver.json").read_text())
        if len(original_journal["effects"]) != 2 or Counter(call["path"] for call in original_journal["calls"]) != {"/execute": 2}:
            raise AssertionError("Source fixture must have exactly two synthetic effects and no queries")

        restored_receiver = Receiver(restored, receiver_port)
        restored_receiver.contract_digest = contract_digest
        restored_receiver.release.set()
        restored_runtime = Runtime(java, jar, restored, restored_receiver)
        restored_runtime.start()
        if restored_runtime.request("GET", "/applications/" + completed_id, user="alice") != completed:
            raise AssertionError("Previously approved application changed during isolated restore")
        after_application = approve(restored_runtime, pending_id)
        after = restored_runtime.request("GET", path, user="finance")
        if len(after["items"]) != 1 or after["items"][0]["id"] != operation_id or after["items"][0]["status"] != "APPLIED" or after["items"][0]["progress"] != "ADVANCED":
            raise AssertionError("Original service result did not advance the restored original workflow")
        save(restored / "recovered-application.json", after_application)
        save(restored / "recovered-status.json", after)
        with restored_receiver.lock:
            journal = json.loads((restored / "receiver.json").read_text())
        calls = journal["calls"][len(original_journal["calls"]):]
        if len(calls) != 1 or calls[0]["path"] != "/query" or calls[0]["request"]["operationId"] != operation_id:
            raise AssertionError("Restored runtime must query the original operation exactly once without resending")
        original_command = next(call["request"] for call in original_journal["calls"] if call["request"]["command"]["id"] == operation_id)
        if calls[0]["request"]["commandDigest"] != original_command["commandDigest"] or "command" in calls[0]["request"] or "inputs" in calls[0]["request"]:
            raise AssertionError("Restored query changed the original digest or resent inputs")
        if journal["effects"] != original_journal["effects"]:
            raise AssertionError("Restored receiver produced another effect or changed original receipts")
        if source_files != {str(p.relative_to(source)): digest(p) for p in source.rglob("*") if p.is_file()}:
            raise AssertionError("Isolated restore modified the source directory")
        result.update(result="PASS", pendingApplicationId=pending_id, completedApplicationId=completed_id, operationId=operation_id,
                      allDatabaseContentsEqualBeforeStartup=True, sourceContentSha256=digest(source_sql),
                      canonicalContentSha256=hashlib.sha256(canonical_content(source_sql)).hexdigest(),
                      receiverEffectsPreserved=2, executeCallsAfterRestore=0, originalNumberQueriesAfterRestore=1,
                      originalHumanApprovalContinued=True, originalSourceUnchanged=True, manifest=str(backup / "manifest.json"))
    finally:
        source_runtime.stop()
        if source_receiver is not None:
            source_receiver.close()
        if restored_runtime is not None:
            restored_runtime.stop()
        if restored_receiver is not None:
            restored_receiver.close()


def main():
    """输出保留在指定临时根目录；失败不覆盖原库或自动删除恢复副本。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    directory = Path(tempfile.mkdtemp(prefix="agentflow-service-restore-", dir="/fyoung/tmp"))
    result = {"directory": str(directory), "jarSha256": digest(jar), "synthetic": True, "result": "RUNNING"}
    save(directory / "result.json", result)
    try:
        verify_pair(args.java, jar, directory, result)
    except Exception as error:
        result.update(result="FAIL", failure=str(error))
        raise
    finally:
        save(directory / "result.json", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
