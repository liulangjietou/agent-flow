"""通过固定 PostgreSQL 17 客户端备份外部数据库，并只恢复到全新数据库。"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

FORMAT = "agentflow-production-postgres-v1"
REQUIRED_TABLES = {"approval_application", "approval_definition", "approval_submission_round",
                   "flyway_schema_history", "act_ru_task", "act_hi_procinst"}
CONFIG_FIELDS = {"host", "port", "database", "schema", "username", "passwordFile", "caFile", "network", "clientImage"}
IMAGE_PATTERN = r"(?:[a-zA-Z0-9./:_-]+@)?sha256:[0-9a-f]{64}"


class RecoveryError(Exception):
    """终端只展示稳定错误码，原始诊断保存在私有目录。@author owlzhangfq@gmail.com"""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def private_directory(value):
    """拒绝复用输出目录，保留失败现场而不覆盖已有备份。"""
    path = Path(value).absolute()
    try:
        path.mkdir(mode=0o700)
    except OSError as error:
        raise RecoveryError("OUTPUT_UNAVAILABLE", "Choose a new output directory under an existing parent.") from error
    return path


def write_json(path, value):
    """完成清单只创建一次，权限保持私有。"""
    with path.open("x", encoding="utf-8") as output:
        os.chmod(path, 0o600)
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def load_config(path):
    """配置只存定位和文件路径；凭据与 CA 不允许被 Docker 挂载语法重新解释。"""
    try:
        source = Path(path)
        if source.stat().st_size > 16384:
            raise ValueError()
        value = json.loads(source.read_text())
        if not isinstance(value, dict) or set(value) != CONFIG_FIELDS:
            raise ValueError()
        for key in ("host", "database", "username", "network"):
            if not isinstance(value[key], str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}", value[key]):
                raise ValueError()
        if not isinstance(value["schema"], str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", value["schema"]):
            raise ValueError()
        if type(value["port"]) is not int or not 1 <= value["port"] <= 65535:
            raise ValueError()
        if not isinstance(value["clientImage"], str) or not re.fullmatch(IMAGE_PATTERN, value["clientImage"]):
            raise ValueError()
        for key in ("passwordFile", "caFile"):
            file = Path(value[key])
            if not file.is_absolute() or not file.is_file() or any(c in str(file) for c in ",\n\r"):
                raise ValueError()
        return value
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise RecoveryError("INVALID_CONFIG", "Invalid connection configuration or credential files.") from error


@contextmanager
def password_file(config):
    """将原始单行密码转为 libpq 文件；密码不进入参数、容器环境或终端。"""
    try:
        with Path(config["passwordFile"]).open("rb") as source:
            raw = source.read(4097)
        if len(raw) > 4096:
            raise ValueError()
        password = raw.decode("utf-8").removesuffix("\n").removesuffix("\r")
        if not password or any(c in password for c in "\r\n\0"):
            raise ValueError()
    except (OSError, ValueError, UnicodeError) as error:
        raise RecoveryError("INVALID_CREDENTIAL_FILE", "The credential file must contain one nonempty line.") from error
    with tempfile.TemporaryDirectory(prefix="agentflow-pgpass-", dir="/fyoung/tmp") as directory:
        path = Path(directory) / "pgpass"
        escaped = password.replace("\\", "\\\\").replace(":", "\\:")
        with path.open("x") as target:
            os.chmod(path, 0o600)
            target.write("*:*:*:*:" + escaped + "\n")
        yield path


class PgClient:
    """只允许本工具的固定客户端命令，强制校验数据库证书和主机名。@author owlzhangfq@gmail.com"""

    def __init__(self, config, credentials, diagnostics):
        self.config = config
        self.credentials = credentials
        self.diagnostics = diagnostics

    def run(self, command, *arguments, database=None, stdin=None, stdout=None):
        """容器名属于本次调用；超时只停止该客户端，目标数据库始终保留。"""
        if command not in {"psql", "pg_dump", "pg_restore", "createdb"}:
            raise ValueError("Unsupported database client command")
        name = "agentflow-pg-client-" + uuid.uuid4().hex
        cfg = self.config
        argv = ["docker", "run", "--rm", "--name", name, "--network", cfg["network"],
                "--user", str(os.getuid()) + ":" + str(os.getgid()),
                "--read-only", "--tmpfs", "/tmp", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true"]
        environment = {"PGHOST": cfg["host"], "PGPORT": str(cfg["port"]), "PGUSER": cfg["username"],
                       "PGDATABASE": database or cfg["database"], "PGSSLMODE": "verify-full",
                       "PGSSLROOTCERT": "/run/secrets/database_ca", "PGPASSFILE": "/run/secrets/pgpass",
                       "PGCONNECT_TIMEOUT": "10", "PGAPPNAME": "agentflow-database-maintenance"}
        for key, value in environment.items():
            argv.extend(["-e", key + "=" + value])
        for source, target in ((self.credentials, "/run/secrets/pgpass"), (cfg["caFile"], "/run/secrets/database_ca")):
            argv.extend(["--mount", f"type=bind,source={source},target={target},readonly"])
        if stdin is not None:
            argv.append("-i")
        # PostgreSQL 在常规参数解析前识别首个 --version；该路径不连接数据库。
        connection_options = [] if arguments == ("--version",) else ["--no-password"]
        argv.extend(["--entrypoint", command, cfg["clientImage"], *connection_options, *arguments])
        try:
            result = subprocess.run(argv, stdin=stdin, stdout=stdout or subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=1800, check=False)
        except subprocess.TimeoutExpired as error:
            # 名称由本调用生成且未复用，只停止本次客户端，不删除或回滚目标数据库。
            try:
                stopped = subprocess.run(["docker", "stop", "--time", "5", name], stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, timeout=15).returncode == 0
            except (subprocess.TimeoutExpired, OSError):
                stopped = False
            write_json(self.diagnostics / (name + "-timeout.json"), {"clientContainer": name, "stopConfirmed": stopped})
            raise RecoveryError("CLIENT_TIMEOUT", "Database client timed out; inspect retained output and target before retrying.") from error
        if result.stderr:
            detail = self.diagnostics / (command + "-" + uuid.uuid4().hex + ".log")
            with detail.open("xb") as output:
                os.chmod(detail, 0o600)
                output.write(result.stderr)
        if result.returncode:
            raise RecoveryError("CLIENT_FAILED", "Database client failed; inspect private diagnostics and retained resources.")
        return result.stdout.decode("utf-8").strip() if result.stdout is not None else ""

    def sql(self, query, database=None):
        """只执行本工具生成的固定 SQL；不开放任意 SQL 参数。"""
        return self.run("psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", query, database=database)

    def require_version(self):
        """固定镜像摘要之外也核对实际工具主版本，避免生成不同版本的归档格式。"""
        if not re.fullmatch(r"pg_dump \(PostgreSQL\) 17(?:\.\d+)?(?: .*)?", self.run("pg_dump", "--version")):
            raise RecoveryError("UNSUPPORTED_CLIENT", "The client image must contain PostgreSQL 17 tools.")

    def inspect(self, database=None, schema=None):
        """核对版本及必要业务表，不读取表单或会话正文。"""
        version = int(self.sql("SHOW server_version_num", database))
        if not 170000 <= version < 180000:
            raise RecoveryError("UNSUPPORTED_DATABASE", "Only PostgreSQL 17 is supported by this backup format.")
        schema = schema or self.config["schema"]
        tables = self.sql("SELECT tablename FROM pg_tables WHERE schemaname='" + schema + "' ORDER BY tablename", database).splitlines()
        if not REQUIRED_TABLES.issubset(tables):
            raise RecoveryError("INVALID_SCHEMA", "Required AgentFlow tables are missing.")
        if self.sql('SELECT bool_and(success) FROM "' + schema + '".flyway_schema_history', database) != "t":
            raise RecoveryError("INVALID_SCHEMA", "Migration history is incomplete.")
        return {"postgresVersion": version, "tables": tables}


def backup(client, output):
    """一次 pg_dump 保存整个数据库的一致逻辑快照，解析归档成功后才写完成清单。"""
    inventory = client.inspect()
    archive = output / "database.dump"
    with archive.open("xb") as target:
        os.chmod(archive, 0o600)
        client.run("pg_dump", "--format=custom", "--no-owner", "--no-acl", "--lock-wait-timeout=10s", stdout=target)
    with archive.open("rb") as source:
        client.run("pg_restore", "--list", stdin=source)
    manifest = {"format": FORMAT, "createdAt": datetime.now(timezone.utc).isoformat(),
                "sourceDatabase": client.config["database"], "schema": client.config["schema"], "postgresVersion": inventory["postgresVersion"],
                "archive": "database.dump", "bytes": archive.stat().st_size, "sha256": digest(archive),
                "clientImage": client.config["clientImage"]}
    write_json(output / "manifest.json", manifest)
    return {"status": "BACKUP_COMPLETE", "bundle": str(output), "bytes": manifest["bytes"], "sha256": manifest["sha256"]}


def check_bundle(value):
    """离线核对固定清单、归档头、长度与摘要；校验和不证明来源可信。"""
    bundle = Path(value).absolute()
    archive, descriptor = bundle / "database.dump", bundle / "manifest.json"
    try:
        if any(p.is_symlink() or not p.is_file() for p in (archive, descriptor)) or descriptor.stat().st_size > 16384:
            raise ValueError()
        data = json.loads(descriptor.read_text())
        if set(data) != {"format", "createdAt", "sourceDatabase", "schema", "postgresVersion", "archive", "bytes", "sha256", "clientImage"}:
            raise ValueError()
        if data["format"] != FORMAT or data["archive"] != "database.dump":
            raise ValueError()
        if not isinstance(data["schema"], str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", data["schema"]):
            raise ValueError()
        if not isinstance(data["sourceDatabase"], str) or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}", data["sourceDatabase"]):
            raise ValueError()
        if type(data["postgresVersion"]) is not int or not 170000 <= data["postgresVersion"] < 180000:
            raise ValueError()
        if type(data["bytes"]) is not int or data["bytes"] < 5 or data["bytes"] != archive.stat().st_size:
            raise ValueError()
        if not isinstance(data["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", data["sha256"]):
            raise ValueError()
        if not isinstance(data["clientImage"], str) or not re.fullmatch(IMAGE_PATTERN, data["clientImage"]):
            raise ValueError()
        if datetime.fromisoformat(data["createdAt"]).tzinfo is None:
            raise ValueError()
        with archive.open("rb") as source:
            if source.read(5) != b"PGDMP":
                raise ValueError()
        if digest(archive) != data["sha256"]:
            raise ValueError()
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise RecoveryError("INVALID_BUNDLE", "Backup bundle is incomplete, unsupported or damaged.") from error
    return data, archive


def restore(client, bundle, output):
    """只创建随机新库，绝不使用 clean、drop 或向已有库恢复。"""
    manifest, archive = check_bundle(bundle)
    version = int(client.sql("SHOW server_version_num"))
    if not 170000 <= version < 180000:
        raise RecoveryError("UNSUPPORTED_DATABASE", "Restore requires PostgreSQL 17.")
    target = "agentflow_restore_" + uuid.uuid4().hex
    if target == manifest["sourceDatabase"] or target == client.config["database"]:
        raise RecoveryError("SOURCE_TARGET_CONFLICT", "Source and target databases must differ.")
    write_json(output / "intent.json", {"targetDatabase": target, "bundleSha256": manifest["sha256"], "startedAt": datetime.now(timezone.utc).isoformat()})
    # CREATE DATABASE 在服务端原子拒绝重名，不能先删除，也不能将重名错误当作成功。
    client.run("createdb", "--maintenance-db", client.config["database"], "--template=template0", target)
    with archive.open("rb") as source:
        client.run("pg_restore", "--dbname", target, "--no-owner", "--no-acl", "--exit-on-error", "--single-transaction", stdin=source)
    inventory = client.inspect(target, manifest["schema"])
    result = {"status": "DATABASE_RESTORED", "targetDatabase": target, "sourceSha256": manifest["sha256"],
              "tables": inventory["tables"], "applicationStarted": False}
    write_json(output / "receipt.json", result)
    return result


def main():
    """入口负责参数和配置校验，执行结果不打印凭据或数据库正文。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "check", "restore"))
    parser.add_argument("--config")
    parser.add_argument("--bundle")
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        if args.action == "check":
            if not args.bundle:
                parser.error("check requires --bundle")
            manifest, _ = check_bundle(args.bundle)
            result = {"status": "BUNDLE_VALID", "sha256": manifest["sha256"]}
        else:
            if not args.config or not args.output or (args.action == "restore" and not args.bundle):
                parser.error("backup/restore requires --config and --output; restore also requires --bundle")
            config = load_config(args.config)
            if args.action == "restore":
                check_bundle(args.bundle)
            output = private_directory(args.output)
            with password_file(config) as credentials:
                client = PgClient(config, credentials, output)
                client.require_version()
                result = backup(client, output) if args.action == "backup" else restore(client, args.bundle, output)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except RecoveryError as error:
        print(json.dumps({"status": "FAILED", "errorCode": error.code, "message": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
