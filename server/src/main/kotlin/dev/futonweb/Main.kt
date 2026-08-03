package dev.futonweb

import dev.futonweb.api.apiRoutes
import dev.futonweb.db.CacheDb
import dev.futonweb.parsers.ParsersService
import dev.futonweb.parsers.WebMangaLoaderContext
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class ErrorDto(val error: String)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val dbPath = System.getenv("DB_PATH") ?: "futon-web.db"
    val log = LoggerFactory.getLogger("futon-web")

    val db = CacheDb(dbPath)
    val service = ParsersService(WebMangaLoaderContext(), db)
    log.info("futon-web starting on port $port with ${service.sources().size} sources")

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json { explicitNulls = true })
        }
        install(CallLogging)
        install(StatusPages) {
            exception<NoSuchElementException> { call, e ->
                call.respond(HttpStatusCode.NotFound, ErrorDto(e.message ?: "not found"))
            }
            exception<NumberFormatException> { call, _ ->
                call.respond(HttpStatusCode.BadRequest, ErrorDto("Malformed id"))
            }
            exception<Throwable> { call, e ->
                LoggerFactory.getLogger("futon-web").error("Unhandled error", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorDto("${e::class.simpleName}: ${e.message}"),
                )
            }
        }
        routing {
            apiRoutes(service, db)
        }
    }.start(wait = true)
}
