package dev.futonweb.parsers

import dev.futonweb.db.CacheDb
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.ConcurrentHashMap

class ParsersService(
    val context: MangaLoaderContext,
    private val db: CacheDb,
) {

    private val parsers = ConcurrentHashMap<MangaParserSource, MangaParser>()

    // pages hold no stable ids we can rebuild from, so they live in memory
    // keyed by chapter and are refetched after a restart
    private val pagesCache = ConcurrentHashMap<Long, List<MangaPage>>()

    fun sources(): List<MangaParserSource> =
        MangaParserSource.entries.filter { it.name != "DUMMY" }

    fun source(name: String): MangaParserSource =
        MangaParserSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw NoSuchElementException("Unknown source: $name")

    fun parser(source: MangaParserSource): MangaParser =
        parsers.computeIfAbsent(source) { context.newParserInstance(it) }

    suspend fun list(source: MangaParserSource, query: String?, offset: Int): List<Manga> {
        val parser = parser(source)
        val order = if (query.isNullOrBlank()) {
            SortOrder.UPDATED.takeIf { it in parser.availableSortOrders }
                ?: parser.availableSortOrders.first()
        } else {
            SortOrder.RELEVANCE.takeIf { it in parser.availableSortOrders }
                ?: parser.availableSortOrders.first()
        }
        val filter = if (query.isNullOrBlank()) MangaListFilter.EMPTY else MangaListFilter(query = query)
        val result = parser.getList(offset, order, filter)
        result.forEach(db::upsertManga)
        return result
    }

    suspend fun details(mangaId: Long): Manga {
        val skeleton = db.getManga(mangaId)
            ?: throw NoSuchElementException("Manga $mangaId is not cached; browse it from its source first")
        val details = parser(skeleton.source as MangaParserSource).getDetails(skeleton)
        db.upsertManga(details)
        return details
    }

    suspend fun pages(chapterId: Long): List<MangaPage> {
        pagesCache[chapterId]?.let { return it }
        val chapter = db.getChapter(chapterId)
            ?: throw NoSuchElementException("Chapter $chapterId is not cached; open its manga details first")
        val pages = parser(chapter.source as MangaParserSource).getPages(chapter)
        pagesCache[chapterId] = pages
        return pages
    }

    suspend fun pageImageUrl(chapterId: Long, index: Int): Pair<String, MangaParserSource> {
        val pages = pages(chapterId)
        val page = pages.getOrNull(index)
            ?: throw NoSuchElementException("Chapter $chapterId has no page $index")
        val source = page.source as MangaParserSource
        return parser(source).getPageUrl(page) to source
    }

    fun chapter(chapterId: Long): MangaChapter? = db.getChapter(chapterId)
}
