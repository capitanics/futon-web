#!/usr/bin/env bash
# Bumps the kotatsu-parsers-redo dependency to the fork's latest commit,
# verifies the backend still compiles, and redeploys the container.
# Usage: scripts/update-parsers.sh [commit-sha]   (default: latest master)
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="kotatsu-redo/kotatsu-parsers-redo"
GRADLE_FILE="server/build.gradle.kts"

sha="${1:-$(curl -sf "https://api.github.com/repos/$REPO/commits/master" \
  -H 'Accept: application/vnd.github.sha' | cut -c1-10)}"
[ -n "$sha" ] || { echo "could not resolve latest commit sha" >&2; exit 1; }

current=$(grep -oP 'kotatsu-parsers-redo:\K[0-9a-f]+' "$GRADLE_FILE")
if [ "$current" = "$sha" ]; then
  echo "already on $sha"
  exit 0
fi

echo "bumping parsers: $current -> $sha"
sed -i "s/kotatsu-parsers-redo:$current/kotatsu-parsers-redo:$sha/" "$GRADLE_FILE"

# fail fast before touching the container (first resolve may wait on JitPack)
echo "compiling against new parsers (JitPack may need a few minutes on a fresh sha)..."
(cd server && ./gradlew compileKotlin --console=plain -q)

echo "rebuilding container..."
docker compose build futon-web
docker compose up -d futon-web

echo "done: parsers at $sha"
echo "if sources you use broke, roll back with: scripts/update-parsers.sh $current"
