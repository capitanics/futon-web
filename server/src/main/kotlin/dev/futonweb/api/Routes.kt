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
        val request = Request.Builder()
            .url(imageUrl)
            .tag(MangaSource::class.java, source)
            .build()
        val response = runInterruptible(Dispatchers.IO) {
            service.context.httpClient.newCall(request).execute()
        }
        response.use {
            if (!it.isSuccessful) {
                call.respond(HttpStatusCode.BadGateway, "Upstream ${it.code} for page $index")
                return@get
            }
            val body = it.requireBody()
            val contentType = body.contentType()?.let { mt -> ContentType.parse(mt.toString()) }
                ?: ContentType.Image.JPEG
            call.respondOutputStream(contentType = contentType) {
                body.byteStream().copyTo(this)
            }
        }
    }
}
