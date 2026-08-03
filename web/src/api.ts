// All ids are opaque strings end-to-end: they are 64-bit values that would
// be corrupted by JS number parsing (see SPEC "ID serialization rule").

export interface Source {
  name: string
  title: string
  locale: string
  broken: boolean
}

export interface Chapter {
  id: string
  title: string | null
  number: number
  volume: number
  branch: string | null
  scanlator: string | null
  uploadDate: number
}

export interface Manga {
  id: string
  title: string
  altTitles: string[]
  coverUrl: string | null
  largeCoverUrl: string | null
  publicUrl: string
  rating: number
  state: string | null
  authors: string[]
  tags: string[]
  description: string | null
  source: string
  chapters: Chapter[] | null
}

export interface Page {
  index: number
  imageUrl: string
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    let detail = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body.error) detail = body.error
    } catch {
      /* not json */
    }
    throw new Error(detail)
  }
  return res.json()
}

export interface HistoryEntry {
  mangaId: string
  chapterId: string
  page: number
  scroll: number
  percent: number
  chapters: number
  updatedAt: number
  manga: Manga | null
}

export const api = {
  sources: () => get<Source[]>('/api/sources'),
  browse: (source: string, query: string, offset = 0) =>
    get<Manga[]>(
      `/api/sources/${encodeURIComponent(source)}/manga?offset=${offset}` +
        (query ? `&query=${encodeURIComponent(query)}` : ''),
    ),
  manga: (id: string) => get<Manga>(`/api/manga/${id}`),
  pages: (chapterId: string) => get<Page[]>(`/api/chapters/${chapterId}/pages`),
  history: () => get<HistoryEntry[]>('/api/history'),
  putProgress: (
    mangaId: string,
    body: { chapterId: string; page: number; scroll: number; percent: number },
  ) =>
    fetch(`/api/history/${mangaId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
}

export function coverUrl(manga: Pick<Manga, 'coverUrl' | 'source'>): string | undefined {
  if (!manga.coverUrl) return undefined
  return `/api/proxy-image?url=${encodeURIComponent(manga.coverUrl)}&source=${encodeURIComponent(manga.source)}`
}
