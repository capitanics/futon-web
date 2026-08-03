import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listHistory, type LocalHistory } from '../db'

export default function Home() {
  const [history, setHistory] = useState<LocalHistory[] | null>(null)

  useEffect(() => {
    listHistory().then(setHistory)
  }, [])

  if (history === null) return <p className="hint">Loading…</p>

  return (
    <div className="page">
      <h1>Continue reading</h1>
      {history.length === 0 ? (
        <p className="hint">
          Nothing here yet — <Link to="/browse">browse a source</Link> and start a chapter.
        </p>
      ) : (
        <div className="grid">
          {history.map((h) => (
            <Link
              key={h.mangaId}
              to={`/read/${h.mangaId}/${h.chapterId}?page=${h.page}`}
              className="card"
            >
              {h.coverUrl ? (
                <img src={h.coverUrl} alt="" loading="lazy" />
              ) : (
                <div className="cover-fallback" />
              )}
              <div className="card-body">
                <span className="card-title">{h.title}</span>
                <span className="card-sub">
                  {h.chapterLabel} · p.{h.page + 1}
                </span>
                <span className="card-sub dim">{h.source}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
