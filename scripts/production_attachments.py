"""组合 PostgreSQL 备份与单机附件目录，只恢复到新数据库和新目录。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import production_database as db

FORMAT = "agentflow-postgres-attachments-v1"
ID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
MAX_BYTES = 100 * 1024 * 1024


def inventory(client, database=None, schema=None):
    """只导出文件身份与指纹，不读取文件名和业务正文；schema 已由连接配置校验。"""
    schema = schema or client.config["schema"]
    query = ('SELECT COALESCE(json_agg(row_to_json(a)),\'[]\'::json)::text FROM '
             '(SELECT id,byte_size AS size,sha256,status FROM "' + schema + '".approval_attachment ORDER BY id) a')
    rows = json.loads(client.sql(query, database))
    validate_records(rows, include_status=True)
    return rows


def validate_records(rows, include_status=False):
    """清单仅接受规范 UUID 和有限字节数，不能构造任意文件路径。"""
    if not isinstance(rows, list):
        raise ValueError("Invalid file inventory")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != ({"id", "size", "sha256", "status"} if include_status else {"id", "size", "sha256"}):
            raise ValueError("Invalid attachment fields")
        if not isinstance(row["id"], str) or not re.fullmatch(ID, row["id"]) or row["id"] in seen:
            raise ValueError("Invalid attachment identity")
        seen.add(row["id"])
        if type(row["size"]) is not int or not 0 <= row["size"] <= MAX_BYTES:
            raise ValueError("Invalid attachment size")
        if not isinstance(row["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]):
            raise ValueError("Invalid attachment digest")
        if include_status and row["status"] not in {"UPLOADING", "READY", "FAILED"}:
            raise ValueError("Invalid attachment state")


def copy_verified(source, record, target=None):
    """以 NOFOLLOW 打开源文件并边复制边校验，目标排他创建；损坏时保留失败现场。"""
    descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    output = None
    try:
        with os.fdopen(descriptor, "rb") as content:
            if not stat.S_ISREG(os.fstat(content.fileno()).st_mode):
                raise ValueError("Attachment is not a regular file")
            if target is not None:
                output = target.open("xb")
                os.chmod(target, 0o600)
            size, digest = 0, hashlib.sha256()
            for chunk in iter(lambda: content.read(1024 * 1024), b""):
                size += len(chunk)
                if size > record["size"]:
                    raise ValueError("Attachment exceeds its recorded size")
                digest.update(chunk)
                if output is not None:
                    output.write(chunk)
            if size != record["size"] or digest.hexdigest() != record["sha256"]:
                raise ValueError("Attachment fingerprint mismatch")
            if output is not None:
                output.flush()
                os.fsync(output.fileno())
    finally:
        if output is not None:
            output.close()


def backup(client, source, output, writes_stopped):
    """操作者停写后一次配套备份，登记集合前后变化或缺失 READY 原文都不写完成清单。"""
    if not writes_stopped:
        raise db.RecoveryError("WRITES_NOT_STOPPED", "Stop all application writers and uploads before creating a paired backup.")
    source = Path(source)
    if not source.is_absolute() or not source.is_dir():
        raise ValueError("An absolute attachment directory is required")
    records = inventory(client)
    result = db.backup(client, db.private_directory(output / "database"))
    files = db.private_directory(output / "files")
    included = []
    for record in records:
        path = source / (record["id"] + ".bin")
        if record["status"] != "READY" and not path.exists() and not path.is_symlink():
            continue
        item = {key: record[key] for key in ("id", "size", "sha256")}
        copy_verified(path, item, files / path.name)
        included.append(item)
    if inventory(client) != records:
        raise db.RecoveryError("ATTACHMENTS_CHANGED", "Attachment metadata changed during backup; stop writers and use a new output directory.")
    manifest = {"format": FORMAT, "createdAt": datetime.now(timezone.utc).isoformat(),
                "databaseSha256": result["sha256"], "files": included}
    db.write_json(output / "manifest.json", manifest)
    return {"status": "DATABASE_AND_ATTACHMENTS_BACKED_UP", "bundle": str(output), "files": len(included), "databaseSha256": result["sha256"]}


def check_bundle(bundle):
    """离线逐份校验文件并绑定数据库归档摘要，不能拼接两个恢复点。"""
    bundle = Path(bundle)
    database, _ = db.check_bundle(bundle / "database")
    descriptor = bundle / "manifest.json"
    if descriptor.is_symlink() or not descriptor.is_file() or descriptor.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Invalid paired manifest")
    manifest = json.loads(descriptor.read_text())
    if set(manifest) != {"format", "createdAt", "databaseSha256", "files"} or manifest["format"] != FORMAT:
        raise ValueError("Unsupported paired bundle")
    if manifest["databaseSha256"] != database["sha256"] or datetime.fromisoformat(manifest["createdAt"]).tzinfo is None:
        raise ValueError("Database recovery point differs")
    validate_records(manifest["files"])
    if (bundle / "files").is_symlink() or not (bundle / "files").is_dir():
        raise ValueError("Invalid file directory")
    for record in manifest["files"]:
        copy_verified(bundle / "files" / (record["id"] + ".bin"), record)
    return manifest, database


def restore(client, bundle, output):
    """先验证配套快照，再创建新库与私有新目录，全部 READY 记录有匹配原文才写最终回执。"""
    bundle = Path(bundle)
    manifest, database = check_bundle(bundle)
    result = db.restore(client, bundle / "database", db.private_directory(output / "database"))
    records = inventory(client, result["targetDatabase"], database["schema"])
    actual = {record["id"]: record for record in records}
    saved = {record["id"]: record for record in manifest["files"]}
    for record in records:
        if record["status"] == "READY" and record["id"] not in saved:
            raise ValueError("Restored database references missing content")
    files = db.private_directory(output / "attachments")
    for record in manifest["files"]:
        row = actual.get(record["id"])
        if row is None or any(row[key] != record[key] for key in ("id", "size", "sha256")):
            raise ValueError("Restored database differs from the file inventory")
        copy_verified(bundle / "files" / (record["id"] + ".bin"), record, files / (record["id"] + ".bin"))
    receipt = {"status": "DATABASE_AND_ATTACHMENTS_RESTORED", "targetDatabase": result["targetDatabase"],
               "attachmentDirectory": str(files.absolute()), "files": len(saved), "applicationStarted": False,
               "databaseSha256": manifest["databaseSha256"]}
    db.write_json(output / "receipt.json", receipt)
    return receipt


def main():
    """CLI 只输出稳定错误，不自动停应用、删除源文件或切换入口。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "check", "restore"))
    parser.add_argument("--config")
    parser.add_argument("--directory")
    parser.add_argument("--output")
    parser.add_argument("--bundle")
    parser.add_argument("--writes-stopped", action="store_true")
    args = parser.parse_args()
    try:
        if args.action == "check":
            if not args.bundle:
                parser.error("check requires --bundle")
            manifest, _ = check_bundle(args.bundle)
            result = {"status": "PAIRED_BUNDLE_VALID", "files": len(manifest["files"])}
        else:
            if not args.config or not args.output or (args.action == "backup" and not args.directory) or (args.action == "restore" and not args.bundle):
                parser.error("backup/restore requires config, output and directory/bundle")
            if args.action == "backup" and not args.writes_stopped:
                parser.error("backup requires --writes-stopped after stopping all writers")
            config = db.load_config(args.config)
            if args.action == "restore":
                check_bundle(args.bundle)
            output = db.private_directory(args.output)
            with db.password_file(config) as credentials:
                client = db.PgClient(config, credentials, output)
                client.require_version()
                result = backup(client, args.directory, output, args.writes_stopped) if args.action == "backup" else restore(client, args.bundle, output)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except db.RecoveryError as error:
        print(json.dumps({"status": "FAILED", "errorCode": error.code, "message": str(error)}))
        return 1
    except (OSError, ValueError, TypeError, KeyError):
        print(json.dumps({"status": "FAILED", "errorCode": "ATTACHMENT_RECOVERY_FAILED", "message": "File or paired inventory validation failed; inspect retained resources."}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
