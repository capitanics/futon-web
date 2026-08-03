import { openDB, type DBSchema } from 'idb'

// Local reading progress. Field names mirror the Kotatsu sync History record
// so Phase 3 reconciliation is a straight merge on updatedAt.
export interface LocalHistory {
  mangaId: string
  chapterId: string
  page: number
  scroll: number
  percent: number
  createdAt: number
  updatedAt: number
  // denormalized for the Home rail
  title: string
  coverUrl: string | null
  source: string
  chapterLabel: string
}

interface FutonDB extends DBSchema {
  history: {
    key: string
    value: LocalHistory
    indexes: { 'by-updated': number }
  }
}

const dbPromise = openDB<FutonDB>('futon-web', 1, {
  upgrade(db) {
    const store = db.createObjectStore('history', { keyPath: 'mangaId' })
    store.createIndex('by-updated', 'updatedAt')
  },
})

export async function getHistory(mangaId: string): Promise<LocalHistory | undefined> {
  return (await dbPromise).get('history', mangaId)
}

export async function listHistory(limit = 30): Promise<LocalHistory[]> {
  const db = await dbPromise
  const all = await db.getAllFromIndex('history', 'by-updated')
  return all.reverse().slice(0, limit)
}

export async function saveProgress(
  entry: Omit<LocalHistory, 'createdAt' | 'updatedAt'>,
): Promise<void> {
  const db = await dbPromise
  const existing = await db.get('history', entry.mangaId)
  const now = Date.now()
  await db.put('history', {
    ...entry,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  })
}
