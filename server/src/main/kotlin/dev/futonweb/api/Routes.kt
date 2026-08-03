package dev.futonweb.api

import dev.futonweb.ImageCache
import dev.futonweb.db.CacheDb
import dev.futonweb.parsers.ParsersService
import dev.futonweb.sync.SyncClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Request
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.requireBody

fun Route.apiRoutes(
    service: ParsersService,
    db: CacheDb,
    sync: SyncClient?,
    images: ImageCache,
) = route("/api") {

    get("/health") {
        call.respond(
            HealthDto(
                status = "ok",
                sources = service.sources().size,
                sync = SyncStatusDto(
                    enabled = sync != null,
                    lastSyncAt = sync?.lastSyncAt ?: 0,
                    lastError = sync?.lastError,
                ),
            ),
        )
    }

    get("/history") {
        val entries = db.getAllHistory()
            .filter { it.deletedAt == 0L }
            .map { r ->
                HistoryEntryDto(
                    mangaId = r.mangaId.toString(),
                    chapterId = r.chapterId.toString(),
                    page = r.page,
                    scroll = r.scroll,
                    percent = r.percent,
                    chapters = r.chapters,
                    updatedAt = r.updatedAt,
                    manga = db.getManga(r.mangaId)?.toDto(),
                )
            }
        call.respond(entries)
    }

    put("/history/{mangaId}") {
        val mangaId = call.parameters["mangaId"]!!.toLong()
        val body = call.receive<ProgressUpdateDto>()
        val existing = db.getHistory(mangaId)
        val now = System.currentTimeMillis()
        db.mergeHistory(
            CacheDb.HistoryRecord(
                mangaId = mangaId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                chapterId = body.chapterId.toLong(),
                page = body.page,
                scroll = body.scroll,
                percent = body.percent,
                chapters = existing?.chapters ?: -1,
                deletedAt = 0,
            ),
        )
        sync?.requestSync()
        call.respond(HttpStatusCode.NoContent)
    }

    get("/sources") {
        call.respond(service.sources().map { it.toDto() })
    }

    get("/sources/{name}/manga") {
        val source = service.source(call.parameters["name"]!!)
        val query = call.request.queryParameters["query"]
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        call.respond(service.list(source, query, offset).map { it.toDto() })
    }

    get("/manga/{id}") {
        val id = call.parameters["id"]!!.toLong()
        val refresh = call.request.queryParameters["refresh"] == "true"
        // stale-while-revalidate: cached copy answers instantly, the source
        // is re-scraped in the background
        val cached = if (refresh) null else service.cachedDetails(id)
        if (cached != null) {
            service.refreshDetailsAsync(id)
            call.respond(cached.toDto(withChapters = true))
        } else {
            call.respond(service.details(id).toDto(withChapters = true))
        }
    }

    get("/chapters/{id}/pages") {
        val id = call.parameters["id"]!!.toLong()
        val pages = service.pages(id)
        call.respond(
            pages.indices.map { i ->
                PageDto(index = i, imageUrl = "/api/image/$id/$i")
            },
        )
    }

    get("/image/{chapterId}/{index}") {
        val chapterId = call.parameters["chapterId"]!!.toLong()
        val index = call.parameters["index"]!!.toInt()
        val (imageUrl, source) = service.pageImageUrl(chapterId, index)
        call.proxyImage(service, images, imageUrl, source)
    }

    // covers and other absolute image urls (hotlink-protected CDNs)
    get("/proxy-image") {
        val url = call.request.queryParameters["url"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDtoCompat("missing url"))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorDtoCompat("absolute http(s) url required"))
        }
        val source = call.request.queryParameters["source"]?.let { service.source(it) }
        call.proxyImage(service, images, url, source)
    }
}

@kotlinx.serialization.Serializable
private data class ErrorDtoCompat(val error: String)

private suspend fun io.ktor.server.application.ApplicationCall.proxyImage(
    service: ParsersService,
    images: ImageCache,
    imageUrl: String,
    source: org.koitharu.kotatsu.parsers.model.MangaParserSource?,
) {
    val cached = images.get(imageUrl)
    val entry = cached ?: run {
        val request = Request.Builder()
            .url(imageUrl)
            .apply { source?.let { tag(MangaSource::class.java, it) } }
            .build()
        val response = runInterruptible(Dispatchers.IO) {
            service.context.httpClient.newCall(request).execute()
        }
        response.use {
            if (!it.isSuccessful) {
                respond(HttpStatusCode.BadGateway, "Upstream ${it.code} for $imageUrl")
                return
            }
            val body = it.requireBody()
            val contentType = body.contentType()?.toString() ?: "image/jpeg"
            runInterruptible(Dispatchers.IO) {
                images.put(imageUrl, contentType, body.byteStream())
            }
        }
    }
    respondOutputStream(contentType = ContentType.parse(entry.contentType)) {
        entry.file.inputStream().use { fis -> fis.copyTo(this) }
    }
}
