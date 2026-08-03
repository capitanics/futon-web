# futon-web

Self-hosted web manga reader that shares reading progress and favourites
with the [Futon](https://github.com/AppFuton/Futon) Android app through a
self-hosted Kotatsu sync server. Full design in [SPEC.md](SPEC.md).

## Run everything (production-ish)

```sh
cp .env.example .env    # fill in secrets; SYNC_EMAIL/PASSWORD = your Futon account
docker compose up -d    # mysql + sync server + futon-web app
```

- Web app: `http://<host>:8090` (`APP_PORT`)
- Sync server (point Futon at this): `http://<host>:8080`

On the phone: Futon → Settings → Services → Synchronization → sign in with
the same email/password and your server address.

## Development

```sh
docker compose up -d db syncserver    # infra only

cd server && ./gradlew run            # backend on :8081 (needs JDK 21; set
                                      # org.gradle.java.home in ~/.gradle if
                                      # your default JDK differs)
# env: SYNC_SERVER_URL=http://localhost:8080 SYNC_EMAIL=... SYNC_PASSWORD=...

cd web && npm install && npm run dev  # SPA on :5173, proxies /api to :8081
```

## Backups

```sh
scripts/backup.sh              # dumps MySQL + copies the SQLite cache
```

Add a cron entry if you want it nightly, e.g.
`0 4 * * * /home/you/dev/futon-web/scripts/backup.sh`.

## HTTPS / chromeless PWA (Tailscale)

The app is installable as a PWA, but Android only opens it as a standalone
chromeless app when served over HTTPS. With Tailscale on the host and phone:

```sh
tailscale serve --bg 8090
```

gives you `https://<host>.<tailnet>.ts.net` for the web app (and the phone
can reach the sync server at the host's Tailscale IP, port 8080, from
anywhere — not just your LAN). Then on the phone: open the HTTPS URL →
browser menu → Add to Home Screen.

## Repo layout

- `server/` — Kotlin/Ktor backend: kotatsu-parsers host, image proxy/cache,
  sync client, REST API, serves the SPA in production.
- `web/` — React SPA: library, browse, details, reader (RTL/LTR/webtoon).
- `docs/` — research notes, protocol fixtures, ID-parity check script.
- `kotatsu-syncserver` runs unmodified from the official Docker image.
