#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_DB:?POSTGRES_DB must be set by the Postgres image}"
: "${POSTGRES_USER:?POSTGRES_USER must be set by the Postgres image}"
: "${FINANCE_APP_PASSWORD:?FINANCE_APP_PASSWORD must be set}"
: "${FINANCE_MIGRATOR_PASSWORD:?FINANCE_MIGRATOR_PASSWORD must be set}"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

psql \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --file="$script_dir/01-finance-app-role.sql.template"

unset FINANCE_APP_PASSWORD
unset FINANCE_MIGRATOR_PASSWORD
