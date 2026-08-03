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
        }
    }

    @Synchronized
    fun upsertManga(manga: Manga) {
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

    @Synchronized
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
