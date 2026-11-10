#!/bin/sh
# Paths come from the executable directory, not the caller's working directory.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
export DOTNET_CLI_TELEMETRY_OPTOUT=1
export DOTNET_ENVIRONMENT="${DOTNET_ENVIRONMENT:-Production}"
export ASPNETCORE_ENVIRONMENT="${ASPNETCORE_ENVIRONMENT:-$DOTNET_ENVIRONMENT}"
export ASPNETCORE_DETAILEDERRORS=false
if [ -n "${AUDIT_URLS:-}" ]; then
  exec "$ROOT/app/AuditRecord.App" --urls "$AUDIT_URLS"
fi
exec "$ROOT/app/AuditRecord.App"
