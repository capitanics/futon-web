export type ReaderMode = 'rtl' | 'ltr' | 'webtoon'
export type FitMode = 'height' | 'width' | 'original'

export interface ReaderSettings {
  mode: ReaderMode
  fit: FitMode
}

// manga is read right-to-left; that is the default, like Kotatsu
const DEFAULTS: ReaderSettings = { mode: 'rtl', fit: 'height' }

interface Stored {
  default: ReaderSettings
  perManga: Record<string, ReaderSettings>
}

function load(): Stored {
  try {
    const raw = localStorage.getItem('reader-settings')
    if (raw) return JSON.parse(raw) as Stored
  } catch {
    /* corrupted: fall through to defaults */
  }
  return { default: DEFAULTS, perManga: {} }
}

export function getReaderSettings(mangaId: string): ReaderSettings {
  const s = load()
  return s.perManga[mangaId] ?? s.default
}

// saving also moves the global default, so the next manga opens the way
// you read the last one
export function saveReaderSettings(mangaId: string, settings: ReaderSettings): void {
  const s = load()
  s.perManga[mangaId] = settings
  s.default = settings
  localStorage.setItem('reader-settings', JSON.stringify(s))
}
