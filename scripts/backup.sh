#!/usr/bin/env bash
# Backs up the sync server MySQL database and the futon-web SQLite cache.
# Usage: scripts/backup.sh [output-dir]   (default: backups/)
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a

out="${1:-backups}/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"

docker compose exec -T db mysqldump \
  -u"${DATABASE_USER:-kotatsu}" -p"$DATABASE_PASSWORD" "${DATABASE_NAME:-kotatsu_db}" \
  > "$out/syncserver.sql"

# best-effort: the app container only exists after `docker compose up futon-web`
docker compose cp futon-web:/data/futon-web.db "$out/futon-web.db" 2>/dev/null ||
  echo "note: futon-web container not running, skipped sqlite copy"

echo "backup written to $out"
