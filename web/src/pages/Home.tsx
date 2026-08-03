import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, coverUrl } from '../api'

export default function Home() {
  const history = useQuery({
    queryKey: ['history'],
    queryFn: api.history,
    refetchInterval: 30_000,
  })

  if (history.isLoading) return <p className="hint">Loading…</p>
  if (history.isError)
    return <p className="error">{(history.error as Error).message}</p>

  const entries = history.data!

  return (
    <div className="page">
      <h1>Continue reading</h1>
      {entries.length === 0 ? (
        <p className="hint">
          Nothing here yet — <Link to="/browse">browse a source</Link> and start a chapter.
        </p>
      ) : (
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
                <span className="card-sub">p.{h.page + 1}</span>
                <span className="card-sub dim">{h.manga?.source ?? ''}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
