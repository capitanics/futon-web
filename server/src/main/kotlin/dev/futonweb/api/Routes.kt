package dev.futonweb.api

import dev.futonweb.db.CacheDb
import dev.futonweb.parsers.ParsersService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Request
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.requireBody

fun Route.apiRoutes(service: ParsersService, db: CacheDb) = route("/api") {

    get("/health") {
        call.respond(HealthDto(status = "ok", sources = service.sources().size))
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
        val details = service.details(id)
        call.respond(details.toDto(withChapters = true))
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
        call.proxyImage(service, imageUrl, source)
    }

    // covers and other absolute image urls (hotlink-protected CDNs)
    get("/proxy-image") {
        val url = call.request.queryParameters["url"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDtoCompat("missing url"))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorDtoCompat("absolute http(s) url required"))
        }
        val source = call.request.queryParameters["source"]?.let { service.source(it) }
        call.proxyImage(service, url, source)
    }
}

@kotlinx.serialization.Serializable
private data class ErrorDtoCompat(val error: String)

private suspend fun io.ktor.server.application.ApplicationCall.proxyImage(
    service: ParsersService,
    imageUrl: String,
    source: org.koitharu.kotatsu.parsers.model.MangaParserSource?,
) {
    run {
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
                return@run
            }
            val body = it.requireBody()
            val contentType = body.contentType()?.let { mt -> ContentType.parse(mt.toString()) }
                ?: ContentType.Image.JPEG
            respondOutputStream(contentType = contentType) {
                body.byteStream().copyTo(this)
            }
        }
    }
}
