import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api, coverUrl } from '../api'

export default function Browse() {
  const [params, setParams] = useSearchParams()
  const source = params.get('source') ?? 'WEEBCENTRAL'
  const query = params.get('query') ?? ''
  const [queryInput, setQueryInput] = useState(query)
  const [sourceFilter, setSourceFilter] = useState('')

  const sources = useQuery({ queryKey: ['sources'], queryFn: api.sources })
  const list = useQuery({
    queryKey: ['browse', source, query],
    queryFn: () => api.browse(source, query),
  })

  const filteredSources = useMemo(() => {
    const all = (sources.data ?? []).filter((s) => !s.broken)
    if (!sourceFilter) return all
    const f = sourceFilter.toLowerCase()
    return all.filter(
      (s) => s.title.toLowerCase().includes(f) || s.name.toLowerCase().includes(f),
    )
  }, [sources.data, sourceFilter])

  return (
    <div className="page">
      <h1>Browse</h1>
      <div className="browse-controls">
        <input
          type="search"
          placeholder="Filter sources…"
          value={sourceFilter}
          onChange={(e) => setSourceFilter(e.target.value)}
        />
        <select
          value={source}
          onChange={(e) => setParams({ source: e.target.value, query })}
        >
          {filteredSources.map((s) => (
            <option key={s.name} value={s.name}>
              {s.title} ({s.locale || '?'})
            </option>
          ))}
        </select>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setParams({ source, query: queryInput })
          }}
        >
          <input
            type="search"
            placeholder={`Search ${source}…`}
            value={queryInput}
            onChange={(e) => setQueryInput(e.target.value)}
          />
        </form>
      </div>

      {list.isLoading && <p className="hint">Loading {source}…</p>}
      {list.isError && <p className="error">{(list.error as Error).message}</p>}
      {list.data && (
        <div className="grid">
          {list.data.map((m) => (
            <Link key={m.id} to={`/manga/${m.id}`} className="card">
              {coverUrl(m) ? (
                <img src={coverUrl(m)} alt="" loading="lazy" />
              ) : (
                <div className="cover-fallback" />
              )}
              <div className="card-body">
                <span className="card-title">{m.title}</span>
                <span className="card-sub dim">{m.source}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
