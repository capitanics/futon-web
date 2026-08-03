# futon-web — Technical Specification

A self-hosted web manga reader that reuses the Kotatsu parser ecosystem and
syncs reading progress bidirectionally with the Futon Android app, via the
standard Kotatsu sync protocol.

Personal, single-user (or few-user) deployment. Not a public service.

---

## 1. Goals and non-goals

### Goals
- Browse, search and read manga from kotatsu-parsers sources in the browser.
- Reading experience adapted to web: paged mode and webtoon (vertical scroll)
  mode, keyboard navigation, image prefetch.
- Bidirectional progress sync with Futon on Android: start a chapter on the
  phone, continue on the web, and vice versa — same chapter, same page.
- Run everything locally via docker-compose; reachable from the phone over
  LAN or Tailscale.

### Non-goals
- Tachiyomi/keiyoushi extension support (Dalvik APKs, impossible on JVM/web).
- Offline downloads / CBZ (browser caching may come later; out of scope).
- Tracker integrations (AniList/MAL) — Futon already does this; the sync
  server is our single source of truth for progress.
- Multi-tenant hosting, public deployment, anti-bot arms race.

---

## 2. Confirmed technical facts (research, 2026-08)

These were verified against the actual source code and are the foundation of
the design:

1. **Sync protocol** ([KotatsuApp/kotatsu-syncserver](https://github.com/KotatsuApp/kotatsu-syncserver),
   Kotlin/Ktor + MySQL, archived Feb 2026 but complete; Docker images exist,
   e.g. `aguslr/kotatsu-syncserver`):
   - `POST /auth` — `{email, password}` → `{token}` (JWT, HMAC256, 30-day
     lifetime, claim `user_id`). Registration happens on first auth when
     `kotatsu.allow_new_register=true`.
   - `GET /resource/history` — full history package for the user.
   - `POST /resource/history` — client sends its `HistoryPackage`; server
     merges (last-writer-wins on `updated_at`) and responds with the merged
     package, or `204 No Content` if the client was already up to date.
   - `GET|POST /resource/favourites` — same pattern, favourites + categories.
   - Auth: `Authorization: Bearer <jwt>` on all `/resource/*` routes.

2. **History record** (`org.kotatsu.model.history.History`):

   ```json
   {
     "manga_id":   123456789,        // Long
     "manga":      { ...Manga... },  // full metadata, server upserts it
     "created_at": 1722700000000,    // epoch millis
     "updated_at": 1722700000000,
     "chapter_id": 987654321,        // Long
     "page":       12,               // Int, current page in chapter
     "scroll":     0.0,              // Float, scroll offset (webtoon)
     "percent":    0.42,             // Float, progress within manga
     "chapters":   -1,               // Int, total chapters (optional)
     "deleted_at": 0                 // tombstone; >0 means deleted
   }
   ```

3. **IDs are deterministic content hashes**, not device-local. From
   kotatsu-parsers `MangaParserEnv.kt`:

   ```kotlin
   fun MangaParser.generateUid(url: String): Long {
       var h = LONG_HASH_SEED
       source.name.forEach { c -> h = 31 * h + c.code }
       url.forEach   { c -> h = 31 * h + c.code }
       return h
   }
   ```

   Same source enum name + same relative URL ⇒ same 64-bit ID on any device.
   **Consequence:** if futon-web uses the same parsers library as Futon,
   `manga_id` / `chapter_id` match automatically and sync needs no mapping
   layer. **Constraint:** we MUST depend on the same parsers fork Futon uses
   (Kotatsu-Parsers-Redo lineage), because a renamed source enum breaks the
   hash equality.

4. **kotatsu-parsers is a plain Kotlin/JVM library** (~1200 sources, OkHttp +
   Jsoup). Consumer implements `MangaLoaderContext`; then
   `newParserInstance(source)` exposes `getList / getDetails / getPages` and
   related APIs. `kotatsu-dl` is reference code for a non-Android integration.

---

## 3. Architecture

```
┌──────────────┐          ┌───────────────────────────┐
│ Futon (phone)│◄────────►│  kotatsu-syncserver        │
└──────────────┘ Tailscale│  (unmodified, Docker)      │
                  or LAN  │  MySQL                     │
                          └────────────▲──────────────┘
                                       │ same protocol:
                                       │ POST /auth, /resource/history
                          ┌────────────┴──────────────┐
  Browser  ◄─────────────►│  futon-web backend         │──────► manga sites
  SPA + IndexedDB   HTTP  │  Kotlin / Ktor / JVM       │  OkHttp (scrape +
                          │  kotatsu-parsers embedded  │  image proxy)
                          │  SQLite (local cache)      │
                          └───────────────────────────┘
```

Three services, one `docker-compose.yaml`:

| Service        | Image                          | Role                                   |
|----------------|--------------------------------|----------------------------------------|
| `mysql`        | `mysql:8`                      | sync server storage                    |
| `syncserver`   | `aguslr/kotatsu-syncserver` (or built from source) | Kotatsu sync protocol, port 8080 |
| `futon-web`    | built here (Ktor fat jar + SPA statics) | parsers host, image proxy, sync client, serves frontend |

The browser talks **only** to `futon-web` (single origin, no CORS anywhere).
`futon-web` talks to the sync server as just another Kotatsu client.
The phone talks only to the sync server.

### 3.1 Backend (`server/`)

Kotlin, Ktor, JDK 21. Modules:

- **ParsersService** — implements `MangaLoaderContext` (OkHttp with cookie
  jar, kotlinx.serialization); caches parser instances per source; wraps
  `getList`, `getDetails`, `getPages`.
- **CacheDB** — SQLite (via JDBC or SQLDelight). Stores manga details and
  chapter lists we have seen (needed to resolve incoming phone-side
  `manga_id`s to something renderable, and to rebuild the `manga` object we
  must embed when POSTing history). Tables: `manga`, `chapters`, `sources`
  (enabled/ordering), `settings`.
- **ImageProxy** — `GET /api/image?url=<encoded>&referer=<encoded>` streams
  the image through OkHttp with the source's `Referer`/UA/cookies.
  Disk LRU cache (configurable size, default 2 GB) so re-reads are instant.
- **SyncClient** — background coroutine. Auths against the sync server
  (stores JWT, re-auths on 401), pushes local progress immediately on write
  (debounced 2 s), polls `GET /resource/history` every 45 s. Merge rule:
  last-writer-wins by `updated_at`, honoring `deleted_at` tombstones — the
  same rule the server applies, so all replicas converge.
- **REST API** (all under `/api`, JSON):

  | Route | Maps to |
  |---|---|
  | `GET  /api/sources` | available sources (name, title, locale, enabled) |
  | `PUT  /api/sources/{name}` | enable/disable/reorder |
  | `GET  /api/sources/{name}/manga?query=&offset=&order=&tags=` | `getList` |
  | `GET  /api/manga/{mangaId}` | `getDetails` (live or cached), incl. chapters |
  | `GET  /api/chapters/{chapterId}/pages` | `getPages` → page image URLs rewritten to `/api/image?...` |
  | `GET  /api/history` | merged local view of history (for library/continue-reading UI) |
  | `PUT  /api/history/{mangaId}` | body `{chapter_id, page, scroll, percent}`; stamps `updated_at=now`, enqueues sync push |
  | `GET  /api/favourites` / `PUT ...` | phase 5 |
  | `GET  /api/health` | liveness + sync server connectivity |

- **Static hosting** — serves the built SPA from `/`.

  **ID serialization rule:** manga/chapter ids are 64-bit and exceed JS
  `Number.MAX_SAFE_INTEGER` (verified with real data — see
  `docs/notes.md`). The `/api` layer serializes all ids as **JSON strings**;
  the SPA treats them as opaque strings end-to-end. Only the backend ever
  parses them into `Long` (for parser calls and sync payloads).

Config via env vars: `SYNC_SERVER_URL`, `SYNC_EMAIL`, `SYNC_PASSWORD`,
`IMAGE_CACHE_DIR`, `IMAGE_CACHE_MAX_MB`, `DB_PATH`, `PORT`.

### 3.2 Frontend (`web/`)

SPA — React + TypeScript + Vite (Svelte acceptable if preferred; decide in
Phase 2). TanStack Query for server state. IndexedDB (via `idb`) mirror of
history for instant resume and reconnect resilience.

Screens:
1. **Library / Home** — "Continue reading" rail (from history, sorted by
   `updated_at`), favourites grid (phase 5).
2. **Browse/Search** — source picker, infinite-scroll catalog, search box,
   order/tag filters.
3. **Manga details** — cover, metadata, chapter list with read-state markers
   (derived from history percent/chapter), "Continue" button.
4. **Reader** — the core deliverable:
   - *Paged mode*: one page (or double-spread later), preload ±2 pages,
     click zones + `←/→/Space` keys, page slider.
   - *Webtoon mode*: virtualized vertical strip, `scroll` float tracked.
   - Progress reporting: on page turn / scroll settle → `PUT /api/history`
     (debounced 2 s) + IndexedDB write.
   - Mode auto-selected from parser hint (webtoon sources) with manual
     override persisted per manga.
5. **Settings** — sync status (last push/pull, account), source management,
   reader defaults.

### 3.3 Sync semantics (end-to-end)

- Every replica (Futon, syncserver DB, futon-web SQLite, browser IndexedDB)
  stores the same record shape keyed by `manga_id`.
- Writes stamp `updated_at = now()` at the writing device.
- Merge everywhere is: keep the record with the greater `updated_at`;
  a tombstone (`deleted_at > 0`) wins like any other write.
- Clock skew between phone and server host is the only hazard — irrelevant in
  practice at ±seconds; NTP on both ends is assumed.
- When futon-web POSTs history it must embed the full `manga` object
  (server upserts metadata) — always available from CacheDB because you can
  only read what you've opened via `getDetails`.

---

## 4. Repository layout

```
futon-web/
├── SPEC.md                  # this file
├── docker-compose.yaml      # mysql + syncserver + futon-web
├── .env.example
├── server/                  # Kotlin/Ktor backend (Gradle)
│   ├── build.gradle.kts
│   └── src/main/kotlin/...
├── web/                     # SPA (Vite)
│   ├── package.json
│   └── src/...
└── docs/
    └── notes.md             # research notes, protocol captures
```

---

## 5. Phases

### Phase 0 — Validate the load-bearing assumptions (no code)
> An evening. Everything else depends on this.

- [x] `docker-compose.yaml` with `mysql` + `syncserver` only; `.env` with
      JWT secret, `allow_new_register=true`.
- [x] Confirm **Futon exposes a custom sync server setting** (verified in source: SyncHostDialogFragment, editable URL, plain HTTP ok) (Settings →
      Services/Synchronization). Point it at the server, create the account.
- [x] Read a chapter in Futon; confirm the row lands in MySQL `history`
      table and `GET /resource/history` (curl with JWT) returns it.
- [x] Identify the **exact parsers dependency** (`com.github.clquwu:kotatsu-parsers-redo:f287c414a6` via JitPack) Futon uses (coordinates +
      version from Futon's `build.gradle` / version catalog) — record in
      `docs/notes.md`.
- [x] Capture 2–3 real history payloads (9 entries, 7 sources, incl. tombstone) in `docs/notes.md` as fixtures.

**Exit criteria:** phone syncs against our server; parsers artifact
identified. If Futon has no sync UI → STOP and re-plan (fallbacks: upstream
Kotatsu app, or backup-file import — decide then).

### Phase 1 — Backend core: parsers online
> ~1 weekend.

- [x] Gradle project, Ktor skeleton, pinned parsers dependency from Phase 0.
- [x] `MangaLoaderContext` implementation (OkHttp, cookie jar, Nashorn for JS).
- [x] Endpoints: `/api/sources`, `/api/sources/{name}/manga`,
      `/api/manga/{id}`, `/api/chapters/{id}/pages`, `/api/health`.
- [x] SQLite CacheDB with `manga` + `chapters` upsert on every `getDetails`.
- [x] ImageProxy with Referer forwarding (disk LRU cache deferred to Phase 4/5).
- [x] Verify ID parity: for one manga, assert backend-computed `manga_id`
      equals the value Futon synced in Phase 0 for the same title/source.
      **This test gates the whole sync design.**

**Exit criteria:** `curl` can browse a source, fetch chapter pages, and load
page images through the proxy; ID-parity test passes.

### Phase 2 — Reader MVP (frontend)
> ~1–2 weekends. The biggest UI effort.

- [ ] Vite + React + TS scaffold, served by Ktor in prod, Vite proxy in dev.
- [ ] Browse/search screen (single source is fine at this point).
- [ ] Details screen with chapter list.
- [ ] Paged reader: preload ±2, keyboard + click-zone nav, page slider,
      loading/error states per page.
- [ ] Local-only progress in IndexedDB (no sync yet): resume works across
      browser reloads.

**Exit criteria:** read a full chapter comfortably in the browser and resume
where you left off (locally).

### Phase 3 — Sync integration (the point of the project)
> ~1 weekend.

- [ ] SyncClient: auth + JWT refresh, push on write (2 s debounce),
      45 s pull loop, LWW merge into SQLite.
- [ ] `PUT /api/history/{mangaId}` wired from the reader; embed cached
      `manga` object on push.
- [ ] `GET /api/history` powering a "Continue reading" rail on Home;
      chapter read-markers on the details screen.
- [ ] IndexedDB ↔ backend reconciliation on app focus/reconnect (same LWW).
- [ ] **End-to-end test:** read pages 1–10 on web → open Futon, lands on
      page 10; read 11–20 on phone → web "Continue" lands on page 20.

**Exit criteria:** the E2E round-trip above works reliably.

### Phase 4 — Webtoon mode + reading polish
> ~1 weekend.

- [ ] Webtoon reader: virtualized vertical strip, `scroll` persistence,
      preload window.
- [ ] Per-manga mode override; auto-detect from parser hints.
- [ ] Prefetch next chapter when >80% through current.
- [ ] Reader settings: fit mode (width/height/original), background color,
      tap-zone layout.
- [ ] Mobile-browser layout pass (usable on the phone too).

### Phase 5 — Library & deployment hardening
> Ongoing / as-needed.

- [ ] Favourites + categories sync (`/resource/favourites`, same LWW pattern);
      favourites grid on Home.
- [ ] Source management UI (enable/disable, ordering, locale filter).
- [ ] futon-web Dockerfile (multi-stage: gradle build + web build → JRE 21
      slim), added to compose; single `docker compose up -d` brings up
      everything.
- [ ] Tailscale note in README (serve/funnel for TLS if wanted).
- [ ] Backup: nightly `mysqldump` + SQLite copy to a local path.
- [ ] (Optional) FlareSolverr sidecar for Cloudflare-walled sources.

---

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Futon dropped the custom-sync-server UI | Fatal to design | Phase 0 gate; fallback: upstream Kotatsu app or backup-file import |
| Parsers fork mismatch → different `source.name` → ID divergence | Duplicate/unsynced history | Pin Futon's exact parsers artifact; Phase 1 ID-parity test |
| Cloudflare-protected sources fail server-side | Some sources unusable on web | Accept; optional FlareSolverr; favor sources that work headless |
| syncserver repo archived (no upstream fixes) | Low — protocol is frozen and tiny | Vendor the source; we can patch/rebuild ourselves |
| Parser API churn between library versions | Build breaks on upgrade | Pin version; upgrade only in lockstep with the Futon app version on the phone |

## 7. Open questions (answer during Phase 0)

1. Exact Maven coordinates/version of Futon's parsers fork ("Kotatsu-Parsers-Redo").
2. Does Futon send/expect anything beyond upstream `History` fields? (Fork
   may have extended the model — check its sync client code if E2E misbehaves;
   server ignores unknown JSON keys via `@JsonIgnoreUnknownKeys`, but a
   missing field on our side would be silently dropped on the phone.)
3. Does the sync client on Android require HTTPS, or is `http://` +
   Tailscale IP accepted? (Kotatsu historically accepted plain HTTP for
   custom servers; verify on Futon.)
