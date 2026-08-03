package dev.futonweb.db

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

private const val SEP = ""

/**
 * Persists every Manga/chapter we have seen so ids arriving from sync (or a
 * browser reload) can be turned back into parser model objects. Tags are not
 * persisted — parsers don't need them to fetch details/pages.
 */
class CacheDb(path: String) {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA journal_mode=WAL").close()
            st.executeUpdate("PRAGMA synchronous=NORMAL")
            st.executeUpdate("PRAGMA busy_timeout=5000")
        }
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS manga(
                    id INTEGER PRIMARY KEY,
                    source TEXT NOT NULL,
                    url TEXT NOT NULL,
                    public_url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    alt_titles TEXT NOT NULL DEFAULT '',
                    cover_url TEXT,
                    large_cover_url TEXT,
                    rating REAL NOT NULL DEFAULT -1,
                    state TEXT,
                    authors TEXT NOT NULL DEFAULT '',
                    description TEXT
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chapters(
                    id INTEGER PRIMARY KEY,
                    manga_id INTEGER NOT NULL,
                    idx INTEGER NOT NULL,
                    title TEXT,
                    number REAL NOT NULL,
                    volume INTEGER NOT NULL,
                    url TEXT NOT NULL,
                    scanlator TEXT,
                    upload_date INTEGER NOT NULL,
                    branch TEXT,
                    source TEXT NOT NULL
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chapters_manga ON chapters(manga_id, idx)")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS history(
                    manga_id INTEGER PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    chapter_id INTEGER NOT NULL,
                    page INTEGER NOT NULL,
                    scroll REAL NOT NULL DEFAULT 0,
                    percent REAL NOT NULL DEFAULT 0,
                    chapters INTEGER NOT NULL DEFAULT -1,
                    deleted_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // raw manga payloads seen from the sync server, replayed verbatim
            // on push so we never degrade metadata (e.g. tags) we can't rebuild
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sync_manga_json(manga_id INTEGER PRIMARY KEY, json TEXT NOT NULL)",
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    sort_key INTEGER NOT NULL DEFAULT 0,
                    track INTEGER NOT NULL DEFAULT 1,
                    title TEXT NOT NULL,
                    ord TEXT NOT NULL DEFAULT 'NEWEST',
                    deleted_at INTEGER NOT NULL DEFAULT 0,
                    show_in_lib INTEGER NOT NULL DEFAULT 1,
                    dirty INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS favourites(
                    manga_id INTEGER NOT NULL,
                    category_id INTEGER NOT NULL,
                    sort_key INTEGER NOT NULL DEFAULT 0,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    deleted_at INTEGER NOT NULL DEFAULT 0,
                    dirty INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(manga_id, category_id)
                )
                """.trimIndent(),
            )
        }
    }

    data class CategoryRecord(
        val id: Int,
        val createdAt: Long,
        val sortKey: Int,
        val track: Boolean,
        val title: String,
        val order: String,
        val deletedAt: Long,
        val showInLib: Boolean,
        val dirty: Boolean = false,
    )

    data class FavouriteRecord(
        val mangaId: Long,
        val categoryId: Int,
        val sortKey: Int,
        val pinned: Boolean,
        val createdAt: Long,
        val deletedAt: Long,
        val dirty: Boolean = false,
    )

    /** Server rows overwrite local ones unless the local row has unsynced web edits. */
    @Synchronized
    fun mergeCategoryFromServer(c: CategoryRecord) {
        val dirty = conn.prepareStatement("SELECT dirty FROM categories WHERE id=?").use { ps ->
            ps.setInt(1, c.id)
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) != 0 }
        }
        if (dirty) return
        conn.prepareStatement(
            """
            INSERT INTO categories(id, created_at, sort_key, track, title, ord, deleted_at, show_in_lib, dirty)
            VALUES(?,?,?,?,?,?,?,?,0)
            ON CONFLICT(id) DO UPDATE SET created_at=excluded.created_at, sort_key=excluded.sort_key,
                track=excluded.track, title=excluded.title, ord=excluded.ord,
                deleted_at=excluded.deleted_at, show_in_lib=excluded.show_in_lib, dirty=0
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, c.id)
            ps.setLong(2, c.createdAt)
            ps.setInt(3, c.sortKey)
            ps.setInt(4, if (c.track) 1 else 0)
            ps.setString(5, c.title)
            ps.setString(6, c.order)
            ps.setLong(7, c.deletedAt)
            ps.setInt(8, if (c.showInLib) 1 else 0)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun mergeFavouriteFromServer(f: FavouriteRecord) {
        val dirty = conn.prepareStatement(
            "SELECT dirty FROM favourites WHERE manga_id=? AND category_id=?",
        ).use { ps ->
            ps.setLong(1, f.mangaId)
            ps.setInt(2, f.categoryId)
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) != 0 }
        }
        if (dirty) return
        writeFavourite(f, dirty = false)
    }

    @Synchronized
    fun writeFavourite(f: FavouriteRecord, dirty: Boolean) {
        conn.prepareStatement(
            """
            INSERT INTO favourites(manga_id, category_id, sort_key, pinned, created_at, deleted_at, dirty)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(manga_id, category_id) DO UPDATE SET sort_key=excluded.sort_key,
                pinned=excluded.pinned, created_at=excluded.created_at,
                deleted_at=excluded.deleted_at, dirty=excluded.dirty
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, f.mangaId)
            ps.setInt(2, f.categoryId)
            ps.setInt(3, f.sortKey)
            ps.setInt(4, if (f.pinned) 1 else 0)
            ps.setLong(5, f.createdAt)
            ps.setLong(6, f.deletedAt)
            ps.setInt(7, if (dirty) 1 else 0)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun insertCategory(c: CategoryRecord) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO categories(id, created_at, sort_key, track, title, ord, deleted_at, show_in_lib, dirty) VALUES(?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setInt(1, c.id)
            ps.setLong(2, c.createdAt)
            ps.setInt(3, c.sortKey)
            ps.setInt(4, if (c.track) 1 else 0)
            ps.setString(5, c.title)
            ps.setString(6, c.order)
            ps.setLong(7, c.deletedAt)
            ps.setInt(8, if (c.showInLib) 1 else 0)
            ps.setInt(9, if (c.dirty) 1 else 0)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun getCategories(includeDeleted: Boolean = false): List<CategoryRecord> =
        conn.prepareStatement(
            "SELECT * FROM categories ${if (includeDeleted) "" else "WHERE deleted_at=0"} ORDER BY sort_key",
        ).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toCategory()) } }
        }

    @Synchronized
    fun getFavourites(includeDeleted: Boolean = false): List<FavouriteRecord> =
        conn.prepareStatement(
            "SELECT * FROM favourites ${if (includeDeleted) "" else "WHERE deleted_at=0"} ORDER BY created_at DESC",
        ).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toFavourite()) } }
        }

    @Synchronized
    fun getDirtyFavourites(): List<FavouriteRecord> =
        conn.prepareStatement("SELECT * FROM favourites WHERE dirty=1").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toFavourite()) } }
        }

    @Synchronized
    fun getDirtyCategories(): List<CategoryRecord> =
        conn.prepareStatement("SELECT * FROM categories WHERE dirty=1").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toCategory()) } }
        }

    @Synchronized
    fun clearFavouriteDirty(mangaId: Long, categoryId: Int) {
        conn.prepareStatement(
            "UPDATE favourites SET dirty=0 WHERE manga_id=? AND category_id=?",
        ).use { ps ->
            ps.setLong(1, mangaId)
            ps.setInt(2, categoryId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun clearCategoryDirty(id: Int) {
        conn.prepareStatement("UPDATE categories SET dirty=0 WHERE id=?").use { ps ->
            ps.setInt(1, id)
            ps.executeUpdate()
        }
    }

    private fun ResultSet.toCategory() = CategoryRecord(
        id = getInt("id"),
        createdAt = getLong("created_at"),
        sortKey = getInt("sort_key"),
        track = getInt("track") != 0,
        title = getString("title"),
        order = getString("ord"),
        deletedAt = getLong("deleted_at"),
        showInLib = getInt("show_in_lib") != 0,
        dirty = getInt("dirty") != 0,
    )

    private fun ResultSet.toFavourite() = FavouriteRecord(
        mangaId = getLong("manga_id"),
        categoryId = getInt("category_id"),
        sortKey = getInt("sort_key"),
        pinned = getInt("pinned") != 0,
        createdAt = getLong("created_at"),
        deletedAt = getLong("deleted_at"),
        dirty = getInt("dirty") != 0,
    )

    data class HistoryRecord(
        val mangaId: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val chapterId: Long,
        val page: Int,
        val scroll: Float,
        val percent: Float,
        val chapters: Int,
        val deletedAt: Long,
    )

    /** Last-writer-wins upsert. Returns true if the row changed. */
    @Synchronized
    fun mergeHistory(r: HistoryRecord): Boolean {
        val existing = getHistory(r.mangaId)
        if (existing != null && existing.updatedAt >= r.updatedAt) return false
        conn.prepareStatement(
            """
            INSERT INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, chapters, deleted_at)
            VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(manga_id) DO UPDATE SET
                created_at=excluded.created_at, updated_at=excluded.updated_at,
                chapter_id=excluded.chapter_id, page=excluded.page, scroll=excluded.scroll,
                percent=excluded.percent, chapters=excluded.chapters, deleted_at=excluded.deleted_at
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, r.mangaId)
            ps.setLong(2, r.createdAt)
            ps.setLong(3, r.updatedAt)
            ps.setLong(4, r.chapterId)
            ps.setInt(5, r.page)
            ps.setFloat(6, r.scroll)
            ps.setFloat(7, r.percent)
            ps.setInt(8, r.chapters)
            ps.setLong(9, r.deletedAt)
            ps.executeUpdate()
        }
        return true
    }

    @Synchronized
    fun getHistory(mangaId: Long): HistoryRecord? =
        conn.prepareStatement("SELECT * FROM history WHERE manga_id=?").use { ps ->
            ps.setLong(1, mangaId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toHistoryRecord() else null }
        }

    @Synchronized
    fun getAllHistory(): List<HistoryRecord> =
        conn.prepareStatement("SELECT * FROM history ORDER BY updated_at DESC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toHistoryRecord()) }
            }
        }

    @Synchronized
    fun saveSyncMangaJson(mangaId: Long, json: String) {
        conn.prepareStatement(
            "INSERT INTO sync_manga_json(manga_id, json) VALUES(?,?) ON CONFLICT(manga_id) DO UPDATE SET json=excluded.json",
        ).use { ps ->
            ps.setLong(1, mangaId)
            ps.setString(2, json)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun getSyncMangaJson(mangaId: Long): String? =
        conn.prepareStatement("SELECT json FROM sync_manga_json WHERE manga_id=?").use { ps ->
            ps.setLong(1, mangaId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun ResultSet.toHistoryRecord() = HistoryRecord(
        mangaId = getLong("manga_id"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        chapterId = getLong("chapter_id"),
        page = getInt("page"),
        scroll = getFloat("scroll"),
        percent = getFloat("percent"),
        chapters = getInt("chapters"),
        deletedAt = getLong("deleted_at"),
    )

    /** Runs [block] in a single transaction — one fsync instead of one per row. */
    private inline fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun upsertManga(manga: Manga) = transaction {
        conn.prepareStatement(
            """
            INSERT INTO manga(id, source, url, public_url, title, alt_titles, cover_url,
                              large_cover_url, rating, state, authors, description)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                source=excluded.source, url=excluded.url, public_url=excluded.public_url,
                title=excluded.title, alt_titles=excluded.alt_titles,
                cover_url=excluded.cover_url, large_cover_url=excluded.large_cover_url,
                rating=excluded.rating, state=excluded.state, authors=excluded.authors,
                description=COALESCE(excluded.description, manga.description)
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, manga.id)
            ps.setString(2, manga.source.name)
            ps.setString(3, manga.url)
            ps.setString(4, manga.publicUrl)
            ps.setString(5, manga.title)
            ps.setString(6, manga.altTitles.joinToString(SEP))
            ps.setString(7, manga.coverUrl)
            ps.setString(8, manga.largeCoverUrl)
            ps.setFloat(9, manga.rating)
            ps.setString(10, manga.state?.name)
            ps.setString(11, manga.authors.joinToString(SEP))
            ps.setString(12, manga.description)
            ps.executeUpdate()
        }
        manga.chapters?.let { replaceChapters(manga.id, it) }
    }

    // always called inside upsertManga's transaction
    private fun replaceChapters(mangaId: Long, chapters: List<MangaChapter>) {
        conn.prepareStatement("DELETE FROM chapters WHERE manga_id=?").use {
            it.setLong(1, mangaId)
            it.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT OR REPLACE INTO chapters(id, manga_id, idx, title, number, volume, url, scanlator, upload_date, branch, source) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            chapters.forEachIndexed { idx, ch ->
                ps.setLong(1, ch.id)
                ps.setLong(2, mangaId)
                ps.setInt(3, idx)
                ps.setString(4, ch.title)
                ps.setFloat(5, ch.number)
                ps.setInt(6, ch.volume)
                ps.setString(7, ch.url)
                ps.setString(8, ch.scanlator)
                ps.setLong(9, ch.uploadDate)
                ps.setString(10, ch.branch)
                ps.setString(11, ch.source.name)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Synchronized
    fun getManga(id: Long): Manga? =
        conn.prepareStatement("SELECT * FROM manga WHERE id=?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toManga() else null }
        }

    @Synchronized
    fun getChapters(mangaId: Long): List<MangaChapter> =
        conn.prepareStatement("SELECT * FROM chapters WHERE manga_id=? ORDER BY idx").use { ps ->
            ps.setLong(1, mangaId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toChapter()) }
            }
        }

    @Synchronized
    fun getChapter(id: Long): MangaChapter? =
        conn.prepareStatement("SELECT * FROM chapters WHERE id=?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toChapter() else null }
        }

    @Synchronized
    fun getMangaIdOfChapter(chapterId: Long): Long? =
        conn.prepareStatement("SELECT manga_id FROM chapters WHERE id=?").use { ps ->
            ps.setLong(1, chapterId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun ResultSet.toManga(): Manga = Manga(
        id = getLong("id"),
        title = getString("title"),
        altTitles = getString("alt_titles").split(SEP).filter { it.isNotEmpty() }.toSet(),
        url = getString("url"),
        publicUrl = getString("public_url"),
        rating = getFloat("rating"),
        contentRating = null,
        coverUrl = getString("cover_url"),
        tags = emptySet(),
        state = getString("state")?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
        authors = getString("authors").split(SEP).filter { it.isNotEmpty() }.toSet(),
        largeCoverUrl = getString("large_cover_url"),
        description = getString("description"),
        chapters = null,
        source = MangaParserSource.valueOf(getString("source")),
    )

    private fun ResultSet.toChapter(): MangaChapter = MangaChapter(
        id = getLong("id"),
        title = getString("title"),
        number = getFloat("number"),
        volume = getInt("volume"),
        url = getString("url"),
        scanlator = getString("scanlator"),
        uploadDate = getLong("upload_date"),
        branch = getString("branch"),
        source = MangaParserSource.valueOf(getString("source")),
    )
}
