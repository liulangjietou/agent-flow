#!/bin/sh
set -eu

# Compose 仅保存密钥文件路径，值在容器启动后读取，不输出到日志或命令参数。
if [ -n "${AGENTFLOW_DATASOURCE_PASSWORD_FILE:-}" ]; then
    if [ -n "${AGENTFLOW_DATASOURCE_PASSWORD:-}" ]; then
        echo "Ambiguous database secret configuration" >&2
        exit 2
    fi
    if [ ! -r "$AGENTFLOW_DATASOURCE_PASSWORD_FILE" ]; then
        echo "Database secret file is not readable" >&2
        exit 2
    fi
    AGENTFLOW_DATASOURCE_PASSWORD=$(cat "$AGENTFLOW_DATASOURCE_PASSWORD_FILE")
    export AGENTFLOW_DATASOURCE_PASSWORD
    if [ -z "$AGENTFLOW_DATASOURCE_PASSWORD" ]; then
        echo "Database secret file is empty" >&2
        exit 2
    fi
fi

if [ -n "${AGENTFLOW_OIDC_CLIENT_SECRET_FILE:-}" ]; then
    if [ -n "${AGENTFLOW_OIDC_CLIENT_SECRET:-}" ]; then
        echo "Ambiguous OIDC secret configuration" >&2
        exit 2
    fi
    if [ ! -r "$AGENTFLOW_OIDC_CLIENT_SECRET_FILE" ]; then
        echo "OIDC secret file is not readable" >&2
        exit 2
    fi
    AGENTFLOW_OIDC_CLIENT_SECRET=$(cat "$AGENTFLOW_OIDC_CLIENT_SECRET_FILE")
    export AGENTFLOW_OIDC_CLIENT_SECRET
    if [ -z "$AGENTFLOW_OIDC_CLIENT_SECRET" ]; then
        echo "OIDC secret file is empty" >&2
        exit 2
    fi
fi

exec java -jar /app/app.jar "$@"
