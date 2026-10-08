#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

mode=${1:-help}
case "$mode" in
  help)
    printf '%s\n' 'Usage: ./scripts/dev.sh legacy-bot|legacy-panel' \
      'SaaS Docker Compose profiles are not available yet; see docs/DEVELOPMENT.md.'
    exit 0
    ;;
  legacy-bot) entrypoint=bot.py ;;
  legacy-panel) entrypoint=panel.py ;;
  *) printf 'Unknown mode: %s\n' "$mode" >&2; exit 2 ;;
esac

if [ -x .venv/bin/python ]; then
  python_exec=.venv/bin/python
elif command -v python >/dev/null 2>&1; then
  python_exec=python
else
  echo 'Python 3.11 or 3.12 is required on PATH.' >&2
  exit 1
fi

if command -v infisical >/dev/null 2>&1; then
  exec infisical run --env=dev -- "$python_exec" "$entrypoint"
fi

echo 'Infisical not found. Starting with process environment and ignored local .env file.' >&2
exec "$python_exec" "$entrypoint"
