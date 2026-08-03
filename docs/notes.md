# Research notes (Phase 0)

Verified 2026-08-03 against source code.

## Local reference clones (shallow, in ~/dev)

- `~/dev/Futon` — the Android app (sync client code, parsers usage patterns).
- `~/dev/kotatsu-parsers-redo` — the parsers fork we depend on. NOTE: clone
  is at current HEAD; the pinned build artifact is commit `f287c414a6`
  (shallow clone can't check that out — `git fetch --depth 1 origin
  f287c414a6` if exact-version reading ever matters).
- `~/dev/kotatsu-syncserver` — sync server source (protocol reference,
  vendored escape hatch since upstream is archived).

## Futon sync client (AppFuton/Futon, devel branch)

- Full Kotatsu-compatible sync client under
  `app/src/main/kotlin/io/github/landwarderer/futon/sync/`
  (SyncAuthApi, SyncController, HistorySyncDto, MangaSyncDto...).
- **Custom sync server: supported.** `SyncHostDialogFragment` shows an
  editable URL field (`UrlValidator().attachToEditText`) plus presets from
  `R.array.sync_url_list` (futon public servers).
- **Plain HTTP accepted**: `SyncSettings.withHttpSchema()` prepends
  `http://` when the entered host has no scheme. No TLS requirement.
- Sync URL is stored per Android account (`KEY_SYNC_URL = "host"`).

## Parsers dependency (MUST match in our backend)

```
repo:     https://jitpack.io
artifact: com.github.clquwu:kotatsu-parsers-redo:f287c414a6
```

From Futon `app/build.gradle:151` + `gradle/libs.versions.toml` (`parsers =
"f287c414a6"`). Version is a git commit short-hash (JitPack). Futon supports
`-DparsersVersionOverride=<sha>` at build time; when the phone app updates,
re-check this hash and bump ours in lockstep (SPEC risk table).

Opt-in flag needed when compiling against it:
`-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi`.

## Sync server deployment (this repo)

- Image: `ghcr.io/kotatsuapp/kotatsu-syncserver:latest` (official CI build,
  repo archived 2026-02 — protocol frozen; vendor source if we ever need to patch).
- `docker compose up -d` → mysql:8.4 (`futon-web-db`) + syncserver
  (`futon-web-sync`) on port 8080. Secrets in `.env` (gitignored).
- Smoke test passed:
  - `POST /auth {"email","password"}` → `{"token": "<jwt>"}` (registers on
    first login while `ALLOW_NEW_REGISTER=true`).
  - `GET /resource/history` + Bearer token → `{"history":[],"timestamp":0}`.
- Test account created: `test@futon.local` / `test-phase0` (disposable).

## Phone setup (manual step)

Host LAN IP: `192.168.2.133` (double-check with `ip -4 addr` — machine also
shows 192.168.2.109 on another interface). In Futon:
Settings → Services (or Sync) → sign in → in the server field enter
`192.168.2.133:8080` (it becomes `http://192.168.2.133:8080`), any
email + password (account auto-registers).

Then read a chapter and verify on this machine:

```sh
TOKEN=$(curl -s -X POST http://localhost:8080/auth \
  -H 'Content-Type: application/json' \
  -d '{"email":"<email-used-on-phone>","password":"<password>"}' | jq -r .token)
curl -s http://localhost:8080/resource/history -H "Authorization: Bearer $TOKEN" | jq .
```

Capture 2–3 payloads below as fixtures once real data arrives.

If the phone can't reach the server: check host firewall (`sudo nft list
ruleset` / iptables) allows inbound 8080, and that both devices share the LAN.

## History payload fixtures

Captured 2026-08-03 from real phone sync (account `teste@teste.com`):
`docs/fixtures/history-package.json` — 9 entries across 7 sources
(MANGATOWN, MANGAGEKO, WEEBCENTRAL ×2, MANGACUTE ×2, YOMUMANGAS, LIKEMANGA),
including one `deleted_at` tombstone (Vinland Saga on MANGAGEKO).

### ID parity — VERIFIED with real data

`docs/fixtures/check_id_parity.py` recomputes `generateUid(source.name +
url)` (seed `1125899906842597`, 31·h+c over UTF-16 code units, 64-bit wrap)
and compares with the synced `manga_id`: **8/9 exact matches**.

The 9th (YOMUMANGAS, `manga_id=7`) is not a failure: that parser prefers the
site API's own numeric series id over the URL hash
(`YomuMangas.kt:436 — id = if (id > 0L) id else generateUid(url)`).
Still content-derived and device-independent. Conclusion: IDs match across
devices as long as both run the same parsers fork — confirmed design basis.

⚠ Gotcha for anyone touching sync tooling: these ids exceed 2^53 — parsing
the JSON with JavaScript `JSON.parse` (or `jq` in some builds) silently
rounds them. Backend must keep them as Kotlin `Long`; any JS-side handling
must treat ids as strings/BigInt. The SPA only ever passes ids through as
opaque strings, so this is a backend/tooling concern.
