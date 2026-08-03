import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, type Chapter, type Page } from '../api'
import { saveProgress } from '../db'
import { getReaderSettings, saveReaderSettings, type ReaderSettings } from '../settings'
import { chapterLabel } from './Manga'

export default function Reader() {
  const { mangaId, chapterId } = useParams<{ mangaId: string; chapterId: string }>()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const initialPage = Number(params.get('page') ?? 0) || 0

  const [settings, setSettings] = useState<ReaderSettings>(() => getReaderSettings(mangaId!))
  const [page, setPage] = useState(initialPage)
  const [showUi, setShowUi] = useState(true)
  const [showSettings, setShowSettings] = useState(false)
  const [failed, setFailed] = useState<Record<number, boolean>>({})

  const manga = useQuery({ queryKey: ['manga', mangaId], queryFn: () => api.manga(mangaId!) })
  const pages = useQuery({ queryKey: ['pages', chapterId], queryFn: () => api.pages(chapterId!) })

  const allChapters = useMemo(() => manga.data?.chapters ?? [], [manga.data])
  const chapter = allChapters.find((c) => c.id === chapterId)
  // navigation stays inside the branch (scanlator/language) being read
  const chapters = useMemo(
    () => (chapter ? allChapters.filter((c) => c.branch === chapter.branch) : allChapters),
    [allChapters, chapter],
  )
  const chapterIndex = chapters.findIndex((c) => c.id === chapterId)
  const total = pages.data?.length ?? 0
  const rtl = settings.mode === 'rtl'

  useEffect(() => {
    setPage(Number(params.get('page') ?? 0) || 0)
    setFailed({})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chapterId])

  const openChapter = useCallback(
    (ch: Chapter, atPage = 0) => {
      navigate(`/read/${mangaId}/${ch.id}${atPage > 0 ? `?page=${atPage}` : ''}`, { replace: true })
    },
    [mangaId, navigate],
  )

  const goTo = useCallback(
    (target: number) => {
      if (!pages.data) return
      if (target < 0) {
        if (chapterIndex > 0) openChapter(chapters[chapterIndex - 1])
        return
      }
      if (target >= pages.data.length) {
        if (chapterIndex >= 0 && chapterIndex < chapters.length - 1) {
          openChapter(chapters[chapterIndex + 1])
        }
        return
      }
      setPage(target)
    },
    [pages.data, chapterIndex, chapters, openChapter],
  )

  const changeSettings = useCallback(
    (patch: Partial<ReaderSettings>) => {
      setSettings((prev) => {
        const next = { ...prev, ...patch }
        saveReaderSettings(mangaId!, next)
        return next
      })
    },
    [mangaId],
  )

  // progress: IndexedDB immediately, server after a short debounce
  useEffect(() => {
    const m = manga.data
    if (!m || !chapter || total === 0) return
    const fraction = chapters.length > 0 ? (chapterIndex + (page + 1) / total) / chapters.length : 0
    const percent = Math.min(1, fraction)
    void saveProgress({
      mangaId: m.id,
      chapterId: chapter.id,
      page,
      scroll: 0,
      percent,
      title: m.title,
      coverUrl: m.coverUrl,
      source: m.source,
      chapterLabel: chapterLabel(chapter),
    })
    const t = setTimeout(() => {
      void api.putProgress(m.id, { chapterId: chapter.id, page, scroll: 0, percent })
    }, 1500)
    return () => clearTimeout(t)
  }, [manga.data, chapter, chapters.length, chapterIndex, page, total])

  // keyboard: arrows follow reading direction, space always advances
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === ' ') {
        e.preventDefault()
        goTo(page + 1)
      } else if (e.key === 'ArrowRight') {
        e.preventDefault()
        goTo(rtl ? page - 1 : page + 1)
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault()
        goTo(rtl ? page + 1 : page - 1)
      } else if (e.key === 'Escape') {
        navigate(`/manga/${mangaId}`)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [goTo, page, rtl, mangaId, navigate])

  // preload neighbours (paged mode)
  useEffect(() => {
    if (settings.mode === 'webtoon' || !pages.data) return
    ;[page + 1, page + 2, page - 1]
      .filter((i) => i >= 0 && i < pages.data.length)
      .forEach((i) => {
        const img = new Image()
        img.src = pages.data[i].imageUrl
      })
  }, [pages.data, page, settings.mode])

  // near the end of the chapter: prefetch the next one
  useEffect(() => {
    if (!pages.data || total === 0) return
    if (page / total < 0.8) return
    const next = chapters[chapterIndex + 1]
    if (!next) return
    void queryClient
      .prefetchQuery({ queryKey: ['pages', next.id], queryFn: () => api.pages(next.id) })
      .then(() => {
        const prefetched = queryClient.getQueryData<Page[]>(['pages', next.id])
        prefetched?.slice(0, 3).forEach((p) => {
          const img = new Image()
          img.src = p.imageUrl
        })
      })
  }, [page, total, chapters, chapterIndex, pages.data, queryClient])

  if (manga.isError || pages.isError) {
    const err = (manga.error ?? pages.error) as Error
    return (
      <div className="reader">
        <div className="reader-message">
          <p className="error">{err.message}</p>
          <Link className="button" to={`/manga/${mangaId}`}>
            Back to chapters
          </Link>
        </div>
      </div>
    )
  }
  if (!pages.data || !manga.data) {
    return (
      <div className="reader">
        <div className="reader-message">
          <span className="loader" aria-label="Loading pages" />
        </div>
      </div>
    )
  }

  return (
    <div className="reader" data-fit={settings.fit}>
      <header className={`reader-bar top ${showUi ? '' : 'hidden'}`}>
        <Link className="reader-back" to={`/manga/${mangaId}`} aria-label="Back to details">
          ‹
        </Link>
        <div className="reader-title">
          <span className="reader-manga">{manga.data.title}</span>
          <select
            className="reader-chapter-select"
            value={chapterId}
            onChange={(e) => {
              const ch = chapters.find((c) => c.id === e.target.value)
              if (ch) openChapter(ch)
            }}
          >
            {chapters.map((ch) => (
              <option key={ch.id} value={ch.id}>
                {chapterLabel(ch)}
              </option>
            ))}
          </select>
        </div>
        <button
          className="icon-button"
          aria-label="Reader settings"
          onClick={() => setShowSettings((v) => !v)}
        >
          ⚙
        </button>
      </header>

      {showSettings && (
        <div className="reader-settings" role="dialog" aria-label="Reader settings">
          <div className="setting-group">
            <span>Mode</span>
            <div className="segmented">
              {(
                [
                  ['rtl', 'Manga ⇠'],
                  ['ltr', '⇢ Comic'],
                  ['webtoon', 'Webtoon ↓'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  className={settings.mode === value ? 'on' : ''}
                  onClick={() => changeSettings({ mode: value })}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
          <div className="setting-group">
            <span>Fit</span>
            <div className="segmented">
              {(
                [
                  ['height', 'Height'],
                  ['width', 'Width'],
                  ['original', 'Original'],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  className={settings.fit === value ? 'on' : ''}
                  onClick={() => changeSettings({ fit: value })}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {settings.mode === 'webtoon' ? (
        <WebtoonStage
          pages={pages.data}
          initialPage={initialPage}
          failed={failed}
          setFailed={setFailed}
          onPageChange={setPage}
          onToggleUi={() => setShowUi((v) => !v)}
        />
      ) : (
        <div className="reader-stage">
          <div
            className="tap-zone left"
            aria-label={rtl ? 'Next page' : 'Previous page'}
            onClick={() => goTo(rtl ? page + 1 : page - 1)}
          />
          <div className="tap-zone center" aria-label="Toggle controls" onClick={() => setShowUi((v) => !v)} />
          <div
            className="tap-zone right"
            aria-label={rtl ? 'Previous page' : 'Next page'}
            onClick={() => goTo(rtl ? page - 1 : page + 1)}
          />
          {failed[page] ? (
            <div className="page-error">
              <p>Page {page + 1} failed to load</p>
              <button className="button" onClick={() => setFailed((f) => ({ ...f, [page]: false }))}>
                Retry
              </button>
            </div>
          ) : (
            <img
              key={pages.data[page].imageUrl}
              className="reader-page"
              src={pages.data[page].imageUrl}
              alt={`Page ${page + 1}`}
              onError={() => setFailed((f) => ({ ...f, [page]: true }))}
            />
          )}
        </div>
      )}

      <footer className={`reader-bar bottom ${showUi ? '' : 'hidden'}`}>
        <button
          className="icon-button"
          aria-label="Previous chapter"
          disabled={chapterIndex <= 0}
          onClick={() => chapterIndex > 0 && openChapter(chapters[chapterIndex - 1])}
        >
          ⏮
        </button>
        {settings.mode !== 'webtoon' && (
          <input
            type="range"
            dir={rtl ? 'rtl' : 'ltr'}
            min={0}
            max={Math.max(0, total - 1)}
            value={page}
            aria-label="Page"
            onChange={(e) => setPage(Number(e.target.value))}
          />
        )}
        <span className="page-counter">
          {page + 1}<em>/</em>{total}
        </span>
        <button
          className="icon-button"
          aria-label="Next chapter"
          disabled={chapterIndex < 0 || chapterIndex >= chapters.length - 1}
          onClick={() => chapterIndex < chapters.length - 1 && openChapter(chapters[chapterIndex + 1])}
        >
          ⏭
        </button>
      </footer>
    </div>
  )
}

function WebtoonStage({
  pages,
  initialPage,
  failed,
  setFailed,
  onPageChange,
  onToggleUi,
}: {
  pages: Page[]
  initialPage: number
  failed: Record<number, boolean>
  setFailed: React.Dispatch<React.SetStateAction<Record<number, boolean>>>
  onPageChange: (page: number) => void
  onToggleUi: () => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const jumped = useRef(false)

  // report the page crossing the middle of the viewport
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            onPageChange(Number((entry.target as HTMLElement).dataset.index))
          }
        }
      },
      { rootMargin: '-45% 0px -45% 0px' },
    )
    container.querySelectorAll('[data-index]').forEach((el) => observer.observe(el))
    return () => observer.disconnect()
  }, [pages, onPageChange])

  // one-time jump to the resume position
  useEffect(() => {
    if (jumped.current || initialPage === 0) return
    const el = containerRef.current?.querySelector(`[data-index="${initialPage}"]`)
    if (el) {
      el.scrollIntoView()
      jumped.current = true
    }
  }, [initialPage, pages])

  return (
    <div className="webtoon" ref={containerRef} onClick={onToggleUi}>
      {pages.map((p, i) =>
        failed[i] ? (
          <div key={p.imageUrl} data-index={i} className="page-error webtoon-slot">
            <p>Page {i + 1} failed to load</p>
            <button
              className="button"
              onClick={(e) => {
                e.stopPropagation()
                setFailed((f) => ({ ...f, [i]: false }))
              }}
            >
              Retry
            </button>
          </div>
        ) : (
          <img
            key={p.imageUrl}
            data-index={i}
            className="webtoon-page"
            src={p.imageUrl}
            alt={`Page ${i + 1}`}
            loading={Math.abs(i - initialPage) < 4 ? 'eager' : 'lazy'}
            onError={() => setFailed((f) => ({ ...f, [i]: true }))}
          />
        ),
      )}
    </div>
  )
}
