"""演示 PostgreSQL 的完整备份与隔离恢复；不覆盖现有项目、容器或卷。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
FORMAT = "agentflow-demo-postgres-v1"
SERVICES = ("database", "server", "web")
DB_NAME = "agentflow"
RUN_LABEL = "io.agentflow.restore.run"
PROJECT_LABEL = "io.agentflow.restore.project"


class RecoveryError(Exception):
    """可向操作者展示的稳定错误，原始诊断单独保存。@author owlzhangfq@gmail.com"""

    def __init__(self, code, message, detail=b""):
        super().__init__(message)
        self.code = code
        self.detail = detail


def docker(*args, stdin=None, stdout=None, timeout=120, stderr_path=None):
    """用参数数组执行 Docker；错误不把 SQL、环境变量或正文输出到终端。"""
    try:
        result = subprocess.run(["docker", *args], stdin=stdin, stdout=stdout or subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        raise RecoveryError("DOCKER_TIMEOUT", "Docker command timed out; inspect retained resources before retrying.", error.stderr or b"") from error
    if stderr_path and result.stderr:
        with stderr_path.open("xb") as stream:
            os.chmod(stderr_path, 0o600); stream.write(result.stderr)
    if result.returncode:
        raise RecoveryError("DOCKER_FAILED", "Docker command failed; see the private diagnostic file.", result.stderr)
    return result.stdout.decode() if result.stdout is not None else ""


def project_name(value):
    if not re.fullmatch(r"[a-z][a-z0-9-]{2,48}", value):
        raise RecoveryError("INVALID_PROJECT", "Use 3-49 lowercase letters, digits or hyphens, starting with a letter.")
    return value


def private_directory(path):
    """目录必须不存在；失败产物保留但不会覆盖先前备份。"""
    path = Path(path).absolute()
    try:
        path.mkdir(mode=0o700)
    except FileExistsError as error:
        raise RecoveryError("OUTPUT_EXISTS", "Output directory already exists; choose a new directory.") from error
    return path


def write_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sql(container, query):
    return docker("exec", container, "psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-U", DB_NAME, "-d", DB_NAME, "-c", query).strip()


def inspect_container(container):
    return json.loads(docker("inspect", container))[0]


def source_services(project):
    result = {}
    for service in SERVICES:
        # 演示 Compose 的标准服务名是唯一入口，历史容器可能仍保留同一组标签。
        value = inspect_container(project + "-" + service + "-1")
        labels = value["Config"].get("Labels") or {}
        if labels.get("com.docker.compose.project") != project or labels.get("com.docker.compose.service") != service:
            raise RecoveryError("SOURCE_IDENTITY_MISMATCH", "The canonical demo service name belongs to a different deployment.")
        result[service] = value
    db = result["database"]
    environment = dict(item.split("=", 1) for item in db["Config"]["Env"] if "=" in item)
    server_environment = dict(item.split("=", 1) for item in result["server"]["Config"]["Env"] if "=" in item)
    if not db["State"]["Running"] or environment.get("POSTGRES_DB") != DB_NAME or environment.get("POSTGRES_USER") != DB_NAME or server_environment.get("AGENTFLOW_DEMO_AUTH") != "true":
        raise RecoveryError("UNSUPPORTED_SOURCE", "The source must be the running AgentFlow demo PostgreSQL database.")
    version = int(sql(db["Id"], "SHOW server_version_num"))
    if not 170000 <= version < 180000:
        raise RecoveryError("UNSUPPORTED_DATABASE", "This recovery format supports PostgreSQL 17 only.")
    return result, version


def backup(project, output):
    """pg_dump 一次备份完整数据库，业务表和 Flowable 共享同一逻辑快照。"""
    services, version = source_services(project_name(project))
    archive = output / "database.dump"
    with archive.open("xb") as stream:
        os.chmod(archive, 0o600)
        docker("exec", services["database"]["Id"], "pg_dump", "-U", DB_NAME, "-d", DB_NAME,
               "--format=custom", "--no-acl", "--lock-wait-timeout=10s", stdout=stream, timeout=600, stderr_path=output / "dump-diagnostics.log")
    with archive.open("rb") as stream:
        docker("exec", "-i", services["database"]["Id"], "pg_restore", "--list", stdin=stream)
    manifest = {"format": FORMAT, "createdAt": datetime.now(timezone.utc).isoformat(), "sourceProject": project,
                "postgresVersion": version, "database": DB_NAME, "archive": "database.dump", "bytes": archive.stat().st_size,
                "sha256": sha256(archive), "images": {key: services[key]["Image"] for key in SERVICES}}
    # 只有归档完整且能解析目录后才写入完成标志，半份文件不构成可恢复备份。
    write_json(output / "manifest.json", manifest)
    return {"status": "BACKUP_COMPLETE", "bundle": str(output), "bytes": manifest["bytes"], "sha256": manifest["sha256"]}


def check_bundle(bundle):
    """固定文件名与字段格式，不接受归档路径、镜像标签或未完成的备份。"""
    bundle = Path(bundle).absolute()
    manifest_file, archive = bundle / "manifest.json", bundle / "database.dump"
    for path in (manifest_file, archive):
        if path.is_symlink() or not path.is_file():
            raise RecoveryError("INVALID_BUNDLE", "Manifest and archive must be regular files, not symbolic links.")
    if manifest_file.stat().st_size > 16384:
        raise RecoveryError("INVALID_BUNDLE", "Backup manifest is too large.")
    try:
        manifest = json.loads(manifest_file.read_text())
        if not isinstance(manifest, dict) or set(manifest) != {"format", "createdAt", "sourceProject", "postgresVersion", "database", "archive", "bytes", "sha256", "images"}:
            raise ValueError()
        if manifest["format"] != FORMAT or manifest["database"] != DB_NAME or manifest["archive"] != "database.dump":
            raise ValueError()
        project_name(manifest["sourceProject"])
        if type(manifest["postgresVersion"]) is not int or not 170000 <= manifest["postgresVersion"] < 180000:
            raise ValueError()
        if type(manifest["bytes"]) is not int or manifest["bytes"] < 5 or manifest["bytes"] != archive.stat().st_size:
            raise ValueError()
        if not isinstance(manifest["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
            raise ValueError()
        if not isinstance(manifest["images"], dict) or set(manifest["images"]) != set(SERVICES):
            raise ValueError()
        if any(not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in manifest["images"].values()):
            raise ValueError()
        if not isinstance(manifest["createdAt"], str) or datetime.fromisoformat(manifest["createdAt"]).tzinfo is None:
            raise ValueError()
    except (ValueError, TypeError, KeyError, RecoveryError) as error:
        raise RecoveryError("INVALID_BUNDLE", "Backup manifest is not supported or does not match the archive.") from error
    with archive.open("rb") as stream:
        magic = stream.read(5)
    if magic != b"PGDMP" or sha256(archive) != manifest["sha256"]:
        raise RecoveryError("CHECKSUM_MISMATCH", "Backup archive is damaged or has changed.")
    return manifest, archive


def require_unused_target(project, manifest, port):
    """在创建任何资源之前拒绝原项目、已有项目/卷和占用端口。"""
    project_name(project)
    if project in ("agentflow-demo", manifest["sourceProject"]):
        raise RecoveryError("SOURCE_TARGET_CONFLICT", "Restore to a new project; the source and default demo are protected.")
    if not 1024 <= port <= 65535:
        raise RecoveryError("INVALID_PORT", "Choose a loopback port between 1024 and 65535.")
    for resource, operation in (("container", "ps"), ("volume", "ls"), ("network", "ls")):
        for label in ("com.docker.compose.project=" + project, PROJECT_LABEL + "=" + project):
            args = ("ps", "-aq") if resource == "container" else (resource, operation, "-q")
            if docker(*args, "--filter", "label=" + label).strip():
                raise RecoveryError("TARGET_EXISTS", "Target project already owns resources; nothing was overwritten.")
    volume = project + "_demo-postgres"
    if volume in docker("volume", "ls", "--format", "{{.Name}}").splitlines():
        raise RecoveryError("TARGET_EXISTS", "Target data volume already exists; choose a new project.")
    names = set(docker("ps", "-a", "--format", "{{.Names}}").splitlines())
    if any(project + suffix in names for suffix in ("-restore-seed", "-database-1", "-server-1", "-web-1")):
        raise RecoveryError("TARGET_EXISTS", "A target container name is already in use.")
    try:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", port))
    except OSError as error:
        raise RecoveryError("PORT_IN_USE", "The requested loopback port is not available.") from error
    # 仅使用备份时的本地不可变镜像，不因标签更新而在恢复时混入新迁移。
    for image_id in manifest["images"].values():
        docker("image", "inspect", image_id)
    return volume


def database_inventory(container):
    """启动应用前核对公共表、每表行数及内容摘要，不输出业务正文。"""
    tables = sql(container, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
    required = {"approval_application", "approval_definition", "approval_submission_round", "flyway_schema_history", "act_ru_task", "act_hi_procinst"}
    if not required.issubset(tables) or sql(container, "SELECT bool_and(success) FROM flyway_schema_history") != "t":
        raise RecoveryError("RESTORE_SCHEMA_INVALID", "Required business, migration or engine tables are missing or invalid.")
    result = {}
    for table in tables:
        identifier = '"' + table.replace('"', '""') + '"'
        count, digest = sql(container, "SELECT count(*), md5(coalesce(string_agg(h,'' ORDER BY h),'')) FROM "
                            + "(SELECT md5(row_to_json(t)::text) h FROM public." + identifier + " t) rows").split("|")
        result[table] = {"rows": int(count), "digest": digest}
    return result


def restore(bundle, project, port, output):
    """恢复到全新卷，先事务恢复并核对，再用原镜像启动新的本机演示实例。"""
    manifest, archive = check_bundle(bundle)
    volume = require_unused_target(project, manifest, port)
    run_id, seed = str(uuid.uuid4()), project + "-restore-seed"
    write_json(output / "intent.json", {"project": project, "volume": volume, "seed": seed, "runId": run_id,
                                        "bundle": str(Path(bundle).absolute()), "port": port})
    docker("volume", "create", "--label", RUN_LABEL + "=" + run_id, "--label", PROJECT_LABEL + "=" + project, volume)
    actual = json.loads(docker("volume", "inspect", volume))[0]
    if (actual.get("Labels") or {}).get(RUN_LABEL) != run_id:
        raise RecoveryError("TARGET_RACE", "The target volume was created by another operation; it was not used.")
    seed_id = docker("run", "-d", "--name", seed, "--network", "none", "--restart", "no",
                     "--label", RUN_LABEL + "=" + run_id, "--label", PROJECT_LABEL + "=" + project,
                     "-e", "POSTGRES_DB=" + DB_NAME, "-e", "POSTGRES_USER=" + DB_NAME,
                     "-e", "POSTGRES_PASSWORD=agentflow-local-demo-only", "-v", volume + ":/var/lib/postgresql/data",
                     manifest["images"]["database"]).strip()
    try:
        deadline = time.monotonic() + 90
        while True:
            try:
                # 初始化临时实例只监听 Unix socket，TCP 就绪后才开始恢复。
                docker("exec", seed_id, "pg_isready", "-h", "127.0.0.1", "-U", DB_NAME, "-d", DB_NAME)
                break
            except RecoveryError:
                if time.monotonic() >= deadline:
                    raise RecoveryError("DATABASE_START_TIMEOUT", "The new database did not become ready.")
                time.sleep(1)
        if sql(seed_id, "SELECT count(*) FROM pg_tables WHERE schemaname='public'") != "0":
            raise RecoveryError("TARGET_NOT_EMPTY", "The target database is not empty; restore was refused.")
        print(json.dumps({"phase": "RESTORING", "project": project}), flush=True)
        with archive.open("rb") as stream:
            docker("exec", "-i", seed_id, "pg_restore", "-U", DB_NAME, "-d", DB_NAME,
                   "--no-owner", "--no-acl", "--exit-on-error", "--single-transaction", stdin=stream, timeout=600)
        inventory = database_inventory(seed_id)
        write_json(output / "database-inventory.json", inventory)
    finally:
        # 即使恢复失败也停止本次临时数据库；保留卷供排查，不执行 DROP/clean/down -v。
        docker("stop", "--timeout", "30", seed_id, timeout=60)
    override = {"services": {name: {"image": image_id, "pull_policy": "never", "labels": {RUN_LABEL: run_id}}
                             for name, image_id in manifest["images"].items()},
                "volumes": {"demo-postgres": {"external": True, "name": volume}}}
    compose_file = output / "compose.restore.json"
    write_json(compose_file, override)
    # 后续命令使用这个独立项目；原 compose 与原数据库不参与恢复写入。
    os.environ["AGENTFLOW_DEMO_PORT"] = str(port)
    command = ["compose", "-p", project, "-f", str(ROOT / "compose.demo.yml"), "-f", str(compose_file)]
    print(json.dumps({"phase": "STARTING_RECOVERED_APP", "project": project}), flush=True)
    docker(*command, "up", "-d", "--no-build", "--no-recreate", "--wait", "--wait-timeout", "180", timeout=240)
    for name in SERVICES:
        ids = docker(*command, "ps", "-q", name).split()
        if len(ids) != 1:
            raise RecoveryError("RESTORED_SERVICE_MISSING", "A restored service is missing.")
        value = inspect_container(ids[0])
        if value["Image"] != manifest["images"][name] or value["Config"]["Labels"].get(RUN_LABEL) != run_id or value["State"].get("Health", {}).get("Status") != "healthy":
            raise RecoveryError("RESTORED_SERVICE_MISMATCH", "Restored service identity or health did not match this operation.")
    receipt = {"status": "RESTORE_COMPLETE", "project": project, "url": "http://127.0.0.1:" + str(port),
               "archiveSha256": manifest["sha256"], "tableCount": len(inventory), "volume": volume,
               "composeOverride": str(compose_file), "sourceProject": manifest["sourceProject"]}
    write_json(output / "receipt.json", receipt)
    return receipt


def main():
    parser = argparse.ArgumentParser(description="Back up and restore the local AgentFlow PostgreSQL demo into a new isolated instance.")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("backup"); create.add_argument("--project", default="agentflow-demo"); create.add_argument("--output", required=True)
    check = commands.add_parser("check"); check.add_argument("--bundle", required=True)
    recover = commands.add_parser("restore"); recover.add_argument("--bundle", required=True); recover.add_argument("--project", required=True)
    recover.add_argument("--port", required=True, type=int); recover.add_argument("--output", required=True)
    args = parser.parse_args()
    output = None
    try:
        if args.command == "check":
            manifest, _ = check_bundle(args.bundle)
            result = {"status": "CHECKSUM_VALID", "sourceProject": manifest["sourceProject"], "sha256": manifest["sha256"], "restoreTested": False}
        else:
            output = private_directory(args.output)
            result = backup(args.project, output) if args.command == "backup" else restore(args.bundle, args.project, args.port, output)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return 0
    except (RecoveryError, OSError, ValueError, KeyboardInterrupt) as error:
        code = error.code if isinstance(error, RecoveryError) else "LOCAL_OPERATION_FAILED"
        result = {"status": "FAILED", "code": code, "message": str(error) if isinstance(error, RecoveryError) else "Local operation failed; inspect the retained output and resources."}
        if output:
            if isinstance(error, RecoveryError) and error.detail:
                diagnostic = output / "diagnostics.log"
                with diagnostic.open("xb") as stream:
                    os.chmod(diagnostic, 0o600); stream.write(error.detail)
            write_json(output / "failure.json", result)
            result["output"] = str(output)
        print(json.dumps(result), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
