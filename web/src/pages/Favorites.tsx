import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, coverUrl } from '../api'

export default function Favorites() {
  const [tab, setTab] = useState<number | 'all'>('all')
  const data = useQuery({
    queryKey: ['favourites'],
    queryFn: api.favourites,
    refetchInterval: 30_000,
  })

  if (data.isLoading) return <p className="hint">Loading…</p>
  if (data.isError) return <p className="error">{(data.error as Error).message}</p>

  const { categories, favourites } = data.data!
  const shown = tab === 'all' ? favourites : favourites.filter((f) => f.categoryId === tab)

  return (
    <div className="page">
      <h1>Favorites</h1>
      {categories.length > 1 && (
        <div className="tabs">
          <button className={tab === 'all' ? 'on' : ''} onClick={() => setTab('all')}>
            All
          </button>
          {categories.map((c) => (
            <button key={c.id} className={tab === c.id ? 'on' : ''} onClick={() => setTab(c.id)}>
              {c.title}
            </button>
          ))}
        </div>
      )}
      {shown.length === 0 ? (
        <p className="hint">
          No favorites yet — open a manga and tap the ♥ to keep it here (and on your phone).
        </p>
      ) : (
        <div className="grid">
          {shown.map((f) => (
            <Link key={`${f.mangaId}:${f.categoryId}`} to={`/manga/${f.mangaId}`} className="card">
              {f.manga && coverUrl(f.manga) ? (
                <img src={coverUrl(f.manga)} alt="" loading="lazy" />
              ) : (
                <div className="cover-fallback" />
              )}
              <div className="card-body">
                <span className="card-title">{f.manga?.title ?? f.mangaId}</span>
                <span className="card-sub dim">{f.manga?.source ?? ''}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
