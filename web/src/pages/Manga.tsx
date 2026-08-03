import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api, coverUrl, type Chapter } from '../api'
import { getHistory, type LocalHistory } from '../db'

export function chapterLabel(ch: Chapter): string {
  if (ch.title) return ch.title
  const parts = []
  if (ch.volume > 0) parts.push(`Vol ${ch.volume}`)
  parts.push(ch.number > 0 ? `Chapter ${ch.number}` : 'Oneshot')
  return parts.join(' ')
}

export default function MangaPage() {
  const { mangaId } = useParams<{ mangaId: string }>()
  const manga = useQuery({
    queryKey: ['manga', mangaId],
    queryFn: () => api.manga(mangaId!),
  })
  const [progress, setProgress] = useState<LocalHistory | undefined>()

  useEffect(() => {
    if (mangaId) getHistory(mangaId).then(setProgress)
  }, [mangaId])

  if (manga.isLoading) return <p className="hint">Loading…</p>
  if (manga.isError) return <p className="error">{(manga.error as Error).message}</p>
  const m = manga.data!
  const chapters = m.chapters ?? []
  const progressIndex = progress
    ? chapters.findIndex((c) => c.id === progress.chapterId)
    : -1

  return (
    <div className="page manga-page">
      <div className="manga-header">
        {coverUrl(m) && <img className="manga-cover" src={coverUrl(m)} alt="" />}
        <div>
          <h1>{m.title}</h1>
          <p className="dim">
            {[m.authors.join(', '), m.state, m.source].filter(Boolean).join(' · ')}
          </p>
          {m.tags.length > 0 && <p className="tags">{m.tags.join(', ')}</p>}
          {m.description && (
            <p
              className="description"
              // parser descriptions are frequently html
              dangerouslySetInnerHTML={{ __html: m.description }}
            />
          )}
          {progress && progressIndex >= 0 && (
            <Link
              className="button"
              to={`/read/${m.id}/${progress.chapterId}?page=${progress.page}`}
            >
              Continue — {chapterLabel(chapters[progressIndex])}, p.{progress.page + 1}
            </Link>
          )}
          {!progress && chapters.length > 0 && (
            <Link className="button" to={`/read/${m.id}/${chapters[0].id}`}>
              Start reading
            </Link>
          )}
        </div>
      </div>

      <h2>{chapters.length} chapters</h2>
      <ol className="chapter-list">
        {chapters.map((ch, i) => (
          <li key={ch.id}>
            <Link
              to={`/read/${m.id}/${ch.id}`}
              className={
                progressIndex >= 0 && i < progressIndex
                  ? 'chapter read'
                  : progressIndex === i
                    ? 'chapter current'
                    : 'chapter'
              }
            >
              <span>{chapterLabel(ch)}</span>
              {ch.uploadDate > 0 && (
                <span className="dim">
                  {new Date(ch.uploadDate).toLocaleDateString()}
                </span>
              )}
            </Link>
          </li>
        ))}
      </ol>
    </div>
  )
}
