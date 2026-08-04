import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, type Chapter, type Page } from '../api'
import { saveProgress } from '../db'
import { getReaderSettings, saveReaderSettings, type ReaderSettings } from '../settings'
import { useFullscreen } from '../useFullscreen'
import { chapterLabel } from './Manga'

// inline SVGs: glyphs like U+1F5D7 render as tofu on Android
function FullscreenIcon({ exit }: { exit?: boolean }) {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {exit ? (
        <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
      ) : (
        <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
      )}
    </svg>
  )
}

export default function Reader() {
  const { mangaId, chapterId } = useParams<{ mangaId: string; chapterId: string }>()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const initialPage = Number(params.get('page') ?? 0) || 0

  const [settings, setSettings] = useState<ReaderSettings>(() => getReaderSettings(mangaId!))
  const [page, setPage] = useState(initialPage)
  const [showUi, setShowUi] = useState(true)
  const [sheet, setSheet] = useState<'none' | 'settings' | 'chapters'>('none')
  const [failed, setFailed] = useState<Record<number, boolean>>({})
  const touchStart = useRef<{ x: number; y: number } | null>(null)
  const fullscreen = useFullscreen()

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
  const percentNow =
    chapters.length > 0 && total > 0
      ? Math.min(1, (chapterIndex + (page + 1) / total) / chapters.length)
      : 0

  useEffect(() => {
    setPage(Number(params.get('page') ?? 0) || 0)
    setFailed({})
    setSheet('none')
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
    void saveProgress({
      mangaId: m.id,
      chapterId: chapter.id,
      page,
      scroll: 0,
      percent: percentNow,
      title: m.title,
      coverUrl: m.coverUrl,
      source: m.source,
      chapterLabel: chapterLabel(chapter),
    })
    const t = setTimeout(() => {
      void api.putProgress(m.id, { chapterId: chapter.id, page, scroll: 0, percent: percentNow })
    }, 1500)
    return () => clearTimeout(t)
  }, [manga.data, chapter, page, total, percentNow])

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
      } else if (e.key === 'f') {
        fullscreen.toggle()
      } else if (e.key === 'Escape') {
        if (sheet !== 'none') setSheet('none')
        else navigate(`/manga/${mangaId}`)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [goTo, page, rtl, mangaId, navigate, sheet, fullscreen])

  // swipe left/right turns pages (paged modes); direction-aware like taps
  const onTouchStartStage = (e: React.TouchEvent) => {
    const t = e.touches[0]
    touchStart.current = { x: t.clientX, y: t.clientY }
  }
  const onTouchEndStage = (e: React.TouchEvent) => {
    const start = touchStart.current
    touchStart.current = null
    if (!start) return
    const t = e.changedTouches[0]
    const dx = t.clientX - start.x
    const dy = t.clientY - start.y
    if (Math.abs(dx) < 60 || Math.abs(dx) < 1.5 * Math.abs(dy)) return
    if (dx < 0) goTo(rtl ? page - 1 : page + 1)
    else goTo(rtl ? page + 1 : page - 1)
  }

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
    if (!pages.data || total === 0 || page / total < 0.8) return
    const next = chapters[chapterIndex + 1]
    if (!next) return
    void queryClient
      .prefetchQuery({ queryKey: ['pages', next.id], queryFn: () => api.pages(next.id) })
      .then(() => {
        queryClient
          .getQueryData<Page[]>(['pages', next.id])
          ?.slice(0, 3)
          .forEach((p) => {
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
          <p className="hint">Fetching pages from the source…</p>
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
          <span className="reader-manga-title">{manga.data.title}</span>
          <span className="reader-chapter-label">{chapter ? chapterLabel(chapter) : ''}</span>
        </div>
      </header>

      {/* compact status line, visible while the bars are hidden */}
      {!showUi && chapter && (
        <div className="reader-status">
          Ch. {chapterIndex + 1}/{chapters.length} · Pg. {page + 1}/{total} ·{' '}
          {Math.round(percentNow * 100)}%
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
        <div className="reader-stage" onTouchStart={onTouchStartStage} onTouchEnd={onTouchEndStage}>
          <div
            className="tap-zone left"
            aria-label={rtl ? 'Next page' : 'Previous page'}
            onClick={() => goTo(rtl ? page + 1 : page - 1)}
          />
          <div
            className="tap-zone center"
            aria-label="Toggle controls"
            onClick={() => setShowUi((v) => !v)}
          />
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
          {page + 1}
          <em>/</em>
          {total}
        </span>
        <button
          className="icon-button"
          aria-label="Next chapter"
          disabled={chapterIndex < 0 || chapterIndex >= chapters.length - 1}
          onClick={() => chapterIndex < chapters.length - 1 && openChapter(chapters[chapterIndex + 1])}
        >
          ⏭
        </button>
        {fullscreen.supported && (
          <button
            className="icon-button"
            aria-label={fullscreen.isFullscreen ? 'Exit full screen' : 'Full screen'}
            onClick={fullscreen.toggle}
          >
            <FullscreenIcon exit={fullscreen.isFullscreen} />
          </button>
        )}
        <button
          className="icon-button"
          aria-label="Chapter list"
          onClick={() => setSheet((s) => (s === 'chapters' ? 'none' : 'chapters'))}
        >
          ☰
        </button>
        <button
          className="icon-button"
          aria-label="Reader settings"
          onClick={() => setSheet((s) => (s === 'settings' ? 'none' : 'settings'))}
        >
          ⚙
        </button>
      </footer>

      {sheet !== 'none' && <div className="sheet-scrim" onClick={() => setSheet('none')} />}

      {sheet === 'settings' && (
        <div className="sheet" role="dialog" aria-label="Reader settings">
          <div className="sheet-handle" />
          <div className="setting-group">
            <span>Read mode</span>
            <div className="segmented">
              {(
                [
                  ['ltr', 'Standard'],
                  ['rtl', 'Right-to-left'],
                  ['webtoon', 'Webtoon'],
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
            <span className="setting-note">Remembered for this manga</span>
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

      {sheet === 'chapters' && (
        <div className="sheet chapters-sheet" role="dialog" aria-label="Chapters">
          <div className="sheet-handle" />
          <ol className="sheet-chapters">
            {chapters.map((ch, i) => (
              <li key={ch.id}>
                <button
                  className={i === chapterIndex ? 'current' : i < chapterIndex ? 'read' : ''}
                  onClick={() => openChapter(ch)}
                >
                  {chapterLabel(ch)}
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
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
