import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, coverUrl, type HistoryEntry } from '../api'

function bucketOf(updatedAt: number, now: number): string {
  const mins = (now - updatedAt) / 60_000
  if (mins < 60) return 'Just now'
  const today = new Date(now)
  const then = new Date(updatedAt)
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  if (updatedAt >= startOfToday) return 'Today'
  if (updatedAt >= startOfToday - 86_400_000) return 'Yesterday'
  const days = Math.floor((startOfToday - updatedAt) / 86_400_000) + 1
  if (days <= 7) return `${days} days ago`
  return then.toLocaleDateString(undefined, { day: 'numeric', month: 'long' })
}

export default function Home() {
  const [search, setSearch] = useState('')
  const history = useQuery({
    queryKey: ['history'],
    queryFn: api.history,
    refetchInterval: 30_000,
  })

  const groups = useMemo(() => {
    const entries = (history.data ?? []).filter((h) =>
      search ? (h.manga?.title ?? '').toLowerCase().includes(search.toLowerCase()) : true,
    )
    const now = Date.now()
    const out: Array<[string, HistoryEntry[]]> = []
    for (const e of entries) {
      const bucket = bucketOf(e.updatedAt, now)
      const last = out[out.length - 1]
      if (last && last[0] === bucket) last[1].push(e)
      else out.push([bucket, [e]])
    }
    return out
  }, [history.data, search])

  if (history.isLoading) return <p className="hint">Loading…</p>
  if (history.isError) return <p className="error">{(history.error as Error).message}</p>

  const latest = history.data?.[0]

  return (
    <div className="page">
      <input
        className="search-pill"
        type="search"
        placeholder="Search your history…"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        aria-label="Search history"
      />

      {groups.length === 0 ? (
        <p className="hint">
          Nothing here yet — <Link to="/browse">browse a source</Link> and start a chapter.
        </p>
      ) : (
        groups.map(([bucket, entries]) => (
          <section key={bucket}>
            <h2 className="bucket">{bucket}</h2>
            <div className="grid">
              {entries.map((h) => (
                <Link
                  key={h.mangaId}
                  to={`/read/${h.mangaId}/${h.chapterId}?page=${h.page}`}
                  className="card"
                >
                  {h.manga && coverUrl(h.manga) ? (
                    <img src={coverUrl(h.manga)} alt="" loading="lazy" />
                  ) : (
                    <div className="cover-fallback" />
                  )}
                  {h.percent > 0 && (
                    <span className="seal" aria-label={`${Math.round(h.percent * 100)}% read`}>
                      {Math.round(h.percent * 100)}%
                    </span>
                  )}
                  <div className="card-body">
                    <span className="card-title">{h.manga?.title ?? h.mangaId}</span>
                    <span className="card-sub dim">
                      p.{h.page + 1} · {h.manga?.source ?? ''}
                    </span>
                  </div>
                </Link>
              ))}
            </div>
          </section>
        ))
      )}

      {latest && !search && (
        <Link
          className="fab"
          to={`/read/${latest.mangaId}/${latest.chapterId}?page=${latest.page}`}
        >
          ▶ Continue
        </Link>
      )}
    </div>
  )
}
