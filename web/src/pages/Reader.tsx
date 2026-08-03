import { useCallback, useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { saveProgress } from '../db'
import { chapterLabel } from './Manga'

export default function Reader() {
  const { mangaId, chapterId } = useParams<{ mangaId: string; chapterId: string }>()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const initialPage = Number(params.get('page') ?? 0) || 0

  const [page, setPage] = useState(initialPage)
  const [showUi, setShowUi] = useState(true)
  const [failed, setFailed] = useState<Record<number, boolean>>({})

  const manga = useQuery({
    queryKey: ['manga', mangaId],
    queryFn: () => api.manga(mangaId!),
  })
  const pages = useQuery({
    queryKey: ['pages', chapterId],
    queryFn: () => api.pages(chapterId!),
  })

  const chapters = manga.data?.chapters ?? []
  const chapterIndex = chapters.findIndex((c) => c.id === chapterId)
  const chapter = chapterIndex >= 0 ? chapters[chapterIndex] : undefined
  const total = pages.data?.length ?? 0

  // chapter changed via next/prev navigation: reset local state
  useEffect(() => {
    setPage(Number(params.get('page') ?? 0) || 0)
    setFailed({})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chapterId])

  const goTo = useCallback(
    (target: number) => {
      if (!pages.data) return
      if (target < 0) {
        if (chapterIndex > 0) {
          navigate(`/read/${mangaId}/${chapters[chapterIndex - 1].id}`, { replace: true })
        }
        return
      }
      if (target >= pages.data.length) {
        if (chapterIndex >= 0 && chapterIndex < chapters.length - 1) {
          navigate(`/read/${mangaId}/${chapters[chapterIndex + 1].id}`, { replace: true })
        }
        return
      }
      setPage(target)
    },
    [pages.data, chapterIndex, chapters, mangaId, navigate],
  )

  // persist progress locally on every page turn (Phase 3 adds server sync)
  useEffect(() => {
    const m = manga.data
    if (!m || !chapter || total === 0) return
    const chapterFraction = chapters.length > 0 ? (chapterIndex + (page + 1) / total) / chapters.length : 0
    void saveProgress({
      mangaId: m.id,
      chapterId: chapter.id,
      page,
      scroll: 0,
      percent: Math.min(1, chapterFraction),
      title: m.title,
      coverUrl: m.coverUrl,
      source: m.source,
      chapterLabel: chapterLabel(chapter),
    })
  }, [manga.data, chapter, chapters.length, chapterIndex, page, total])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight' || e.key === ' ') {
        e.preventDefault()
        goTo(page + 1)
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault()
        goTo(page - 1)
      } else if (e.key === 'Escape') {
        navigate(`/manga/${mangaId}`)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [goTo, page, mangaId, navigate])

  // preload +-2 pages around the current one
  const preloadUrls = useMemo(() => {
    if (!pages.data) return []
    return [page + 1, page + 2, page - 1, page - 2]
      .filter((i) => i >= 0 && i < pages.data.length)
      .map((i) => pages.data[i].imageUrl)
  }, [pages.data, page])

  useEffect(() => {
    preloadUrls.forEach((url) => {
      const img = new Image()
      img.src = url
    })
  }, [preloadUrls])

  if (manga.isError || pages.isError) {
    const err = (manga.error ?? pages.error) as Error
    return (
      <div className="reader">
        <p className="error">{err.message}</p>
        <Link className="button" to={`/manga/${mangaId}`}>
          Back
        </Link>
      </div>
    )
  }
  if (!pages.data || !manga.data) {
    return (
      <div className="reader">
        <p className="hint">Loading pages…</p>
      </div>
    )
  }

  const current = pages.data[page]

  return (
    <div className="reader">
      {showUi && (
        <header className="reader-bar">
          <Link to={`/manga/${mangaId}`}>← {manga.data.title}</Link>
          <span>
            {chapter ? chapterLabel(chapter) : ''} — {page + 1}/{total}
          </span>
        </header>
      )}

      <div className="reader-stage">
        <div className="tap-zone left" onClick={() => goTo(page - 1)} />
        <div className="tap-zone center" onClick={() => setShowUi((v) => !v)} />
        <div className="tap-zone right" onClick={() => goTo(page + 1)} />
        {failed[page] ? (
          <div className="page-error">
            <p>Failed to load page {page + 1}</p>
            <button
              className="button"
              onClick={() => setFailed((f) => ({ ...f, [page]: false }))}
            >
              Retry
            </button>
          </div>
        ) : (
          <img
            key={current.imageUrl}
            className="reader-page"
            src={current.imageUrl}
            alt={`page ${page + 1}`}
            onError={() => setFailed((f) => ({ ...f, [page]: true }))}
          />
        )}
      </div>

      {showUi && (
        <footer className="reader-bar bottom">
          <button className="button ghost" onClick={() => goTo(page - 1)} disabled={page === 0 && chapterIndex <= 0}>
            ‹
          </button>
          <input
            type="range"
            min={0}
            max={Math.max(0, total - 1)}
            value={page}
            onChange={(e) => setPage(Number(e.target.value))}
          />
          <button className="button ghost" onClick={() => goTo(page + 1)}>
            ›
          </button>
        </footer>
      )}
    </div>
  )
}
