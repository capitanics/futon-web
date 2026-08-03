import { useEffect, useMemo, useState } from 'react'
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
  const serverHistory = useQuery({ queryKey: ['history'], queryFn: api.history })
  const [localProgress, setLocalProgress] = useState<LocalHistory | undefined>()
  const [branch, setBranch] = useState<string | null | undefined>(undefined)

  useEffect(() => {
    if (mangaId) getHistory(mangaId).then(setLocalProgress)
  }, [mangaId])

  // server history (merged with the phone) wins over the browser-local copy
  // when it is fresher
  const serverEntry = serverHistory.data?.find((h) => h.mangaId === mangaId)
  const progress =
    serverEntry && (!localProgress || serverEntry.updatedAt >= localProgress.updatedAt)
      ? { chapterId: serverEntry.chapterId, page: serverEntry.page }
      : localProgress
        ? { chapterId: localProgress.chapterId, page: localProgress.page }
        : undefined

  const allChapters = useMemo(() => manga.data?.chapters ?? [], [manga.data])

  const branches = useMemo(() => {
    const counts = new Map<string | null, number>()
    for (const ch of allChapters) counts.set(ch.branch, (counts.get(ch.branch) ?? 0) + 1)
    return [...counts.entries()].sort((a, b) => b[1] - a[1])
  }, [allChapters])

  // default branch: the one being read, else the largest
  const activeBranch =
    branch !== undefined
      ? branch
      : (progress && allChapters.find((c) => c.id === progress.chapterId)?.branch) ??
        branches[0]?.[0] ??
        null

  const chapters = useMemo(
    () => allChapters.filter((c) => c.branch === activeBranch),
    [allChapters, activeBranch],
  )

  if (manga.isLoading) return <p className="hint">Loading…</p>
  if (manga.isError) return <p className="error">{(manga.error as Error).message}</p>
  const m = manga.data!
  const cover = coverUrl(m)
  const progressIndex = progress ? chapters.findIndex((c) => c.id === progress.chapterId) : -1

  return (
    <div className="page manga-page">
      <div
        className="manga-hero"
        style={cover ? ({ '--hero-cover': `url("${cover}")` } as React.CSSProperties) : undefined}
      >
        <div className="manga-header">
          {cover && <img className="manga-cover" src={cover} alt="" />}
          <div>
            <h1>{m.title}</h1>
            <p className="meta-line">
              {[m.authors.join(', '), m.state, m.source].filter(Boolean).join(' · ')}
            </p>
            {m.tags.length > 0 && (
              <div className="chips">
                {m.tags.map((t) => (
                  <span key={t} className="chip">
                    {t}
                  </span>
                ))}
              </div>
            )}
            {progress && progressIndex >= 0 ? (
              <Link
                className="button"
                to={`/read/${m.id}/${progress.chapterId}?page=${progress.page}`}
              >
                Continue · {chapterLabel(chapters[progressIndex])}, p.{progress.page + 1}
              </Link>
            ) : chapters.length > 0 ? (
              <Link className="button" to={`/read/${m.id}/${chapters[0].id}`}>
                Start reading
              </Link>
            ) : null}
          </div>
        </div>
      </div>

      {m.description && (
        <p className="description" dangerouslySetInnerHTML={{ __html: m.description }} />
      )}

      <div className="chapters-head">
        <h2>{chapters.length} chapters</h2>
        {branches.length > 1 && (
          <select
            className="branch-select"
            value={activeBranch ?? ''}
            onChange={(e) => setBranch(e.target.value === '' ? null : e.target.value)}
            aria-label="Translation / scanlator"
          >
            {branches.map(([b, count]) => (
              <option key={b ?? ''} value={b ?? ''}>
                {(b ?? 'Default') + ` (${count})`}
              </option>
            ))}
          </select>
        )}
      </div>
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
                <span className="dim">{new Date(ch.uploadDate).toLocaleDateString()}</span>
              )}
            </Link>
          </li>
        ))}
      </ol>
    </div>
  )
}
