package dev.futonweb.sync

import dev.futonweb.db.CacheDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class SyncClient(
    private val baseUrl: String,
    private val email: String,
    private val password: String,
    private val db: CacheDb,
) {
    private val log = LoggerFactory.getLogger("sync")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()
    private val mutex = Mutex()
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var token: String? = null

    @Volatile
    var lastSyncAt: Long = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun requestSync() {
        trigger.trySend(Unit)
    }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                // wake up on demand (debounced) or every 45 s
                val poked = withTimeoutOrNull(45_000) { trigger.receive() } != null
                if (poked) delay(2_000)
                try {
                    syncNow()
                    lastError = null
                } catch (e: Exception) {
                    lastError = e.message
                    log.warn("sync failed: ${e.message}")
                    delay(15_000)
                }
            }
        }
    }

    suspend fun syncNow(): Unit = mutex.withLock {
        syncHistoryResource()
        syncFavouritesResource()
        lastSyncAt = System.currentTimeMillis()
    }

    private suspend fun syncHistoryResource() {
        val server = pull()
        // remember raw manga payloads and index metadata for rendering
        for (h in server.history) {
            db.saveSyncMangaJson(h.mangaId, json.encodeToString(SyncManga.serializer(), h.manga))
            if (db.getManga(h.mangaId) == null) {
                h.manga.toParserManga()?.let { db.upsertManga(it) }
            }
        }
        val serverByManga = server.history.associateBy { it.mangaId }
        // LWW-merge server records into local
        for (h in server.history) {
            db.mergeHistory(h.toRecord())
        }
        // push ONLY records strictly newer than the server's copy: the server
        // overwrites blindly, so sending anything stale would clobber phone progress
        val toPush = db.getAllHistory().filter { local ->
            val remote = serverByManga[local.mangaId]
            remote == null || local.updatedAt > remote.updatedAt
        }
        if (toPush.isNotEmpty()) {
            log.info("pushing ${toPush.size} history record(s)")
            val payload = SyncHistoryPackage(
                history = toPush.mapNotNull { it.toSyncHistory() },
                timestamp = System.currentTimeMillis(),
            )
            val merged = post(payload)
            merged?.history?.forEach { h ->
                db.saveSyncMangaJson(h.mangaId, json.encodeToString(SyncManga.serializer(), h.manga))
                db.mergeHistory(h.toRecord())
            }
        }
    }

    private suspend fun syncFavouritesResource() {
        val server = pullFavourites()
        for (c in server.categories) {
            db.mergeCategoryFromServer(c.toRecord())
        }
        for (f in server.favourites) {
            db.saveSyncMangaJson(f.mangaId, json.encodeToString(SyncManga.serializer(), f.manga))
            if (db.getManga(f.mangaId) == null) {
                f.manga.toParserManga()?.let { db.upsertManga(it) }
            }
            db.mergeFavouriteFromServer(f.toRecord())
        }
        val dirtyCategories = db.getDirtyCategories()
        val dirtyFavourites = db.getDirtyFavourites()
        if (dirtyCategories.isEmpty() && dirtyFavourites.isEmpty()) return
        log.info("pushing ${dirtyFavourites.size} favourite(s), ${dirtyCategories.size} categorie(s)")
        val payload = SyncFavouritesPackage(
            categories = dirtyCategories.map { it.toSync() },
            favourites = dirtyFavourites.mapNotNull { it.toSyncFavourite() },
            timestamp = System.currentTimeMillis(),
        )
        postFavourites(payload)
        dirtyCategories.forEach { db.clearCategoryDirty(it.id) }
        dirtyFavourites.forEach { db.clearFavouriteDirty(it.mangaId, it.categoryId) }
    }

    // ---- protocol ----

    private suspend fun auth(): String {
        val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
        val request = Request.Builder()
            .url("$baseUrl/auth")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val response = runInterruptible(Dispatchers.IO) { http.newCall(request).execute() }
        response.use {
            check(it.isSuccessful) { "auth failed: HTTP ${it.code}" }
            val parsed = json.decodeFromString(AuthResponse.serializer(), it.body.string())
            token = parsed.token
            return parsed.token
        }
    }

    private suspend fun <T> withAuth(block: suspend (String) -> T?): T? {
        val t = token ?: auth()
        val first = block(t)
        if (first != null) return first
        // null signals 401 from the executor below: re-auth once
        return block(auth())
    }

    private suspend fun pull(): SyncHistoryPackage = withAuth { t ->
        val request = Request.Builder()
            .url("$baseUrl/resource/history")
            .header("Authorization", "Bearer $t")
            .build()
        val response = runInterruptible(Dispatchers.IO) { http.newCall(request).execute() }
        response.use {
            when {
                it.code == 401 -> null
                it.isSuccessful ->
                    json.decodeFromString(SyncHistoryPackage.serializer(), it.body.string())
                else -> error("history pull failed: HTTP ${it.code}")
            }
        }
    } ?: error("authentication rejected")

    /** POST our records. Returns the merged package, or null on 204 (server had identical content). */
    private suspend fun post(payload: SyncHistoryPackage): SyncHistoryPackage? {
        val result = withAuth { t ->
            val body = json.encodeToString(SyncHistoryPackage.serializer(), payload)
            val request = Request.Builder()
                .url("$baseUrl/resource/history")
                .header("Authorization", "Bearer $t")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val response = runInterruptible(Dispatchers.IO) { http.newCall(request).execute() }
            response.use {
                when {
                    it.code == 401 -> null
                    it.code == 204 -> Box(null)
                    it.isSuccessful ->
                        Box(json.decodeFromString(SyncHistoryPackage.serializer(), it.body.string()))
                    else -> error("history push failed: HTTP ${it.code}")
                }
            }
        } ?: error("authentication rejected")
        return result.value
    }

    private class Box(val value: SyncHistoryPackage?)

    private suspend fun pullFavourites(): SyncFavouritesPackage = withAuth { t ->
        val request = Request.Builder()
            .url("$baseUrl/resource/favourites")
            .header("Authorization", "Bearer $t")
            .build()
        val response = runInterruptible(Dispatchers.IO) { http.newCall(request).execute() }
        response.use {
            when {
                it.code == 401 -> null
                it.isSuccessful ->
                    json.decodeFromString(SyncFavouritesPackage.serializer(), it.body.string())
                else -> error("favourites pull failed: HTTP ${it.code}")
            }
        }
    } ?: error("authentication rejected")

    private suspend fun postFavourites(payload: SyncFavouritesPackage) {
        withAuth { t ->
            val body = json.encodeToString(SyncFavouritesPackage.serializer(), payload)
            val request = Request.Builder()
                .url("$baseUrl/resource/favourites")
                .header("Authorization", "Bearer $t")
                .post(body.toRequestBody(jsonMedia))
                .build()
            val response = runInterruptible(Dispatchers.IO) { http.newCall(request).execute() }
            response.use {
                when {
                    it.code == 401 -> null
                    it.isSuccessful || it.code == 204 -> Unit
                    else -> error("favourites push failed: HTTP ${it.code}")
                }
            }
        } ?: error("authentication rejected")
    }

    // ---- mapping ----

    private fun SyncHistory.toRecord() = CacheDb.HistoryRecord(
        mangaId = mangaId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chapterId = chapterId,
        page = page,
        scroll = scroll,
        percent = percent,
        chapters = chapters,
        deletedAt = deletedAt,
    )

    private fun CacheDb.HistoryRecord.toSyncHistory(): SyncHistory? {
        val manga = db.getSyncMangaJson(mangaId)
            ?.let { runCatching { json.decodeFromString(SyncManga.serializer(), it) }.getOrNull() }
            ?: db.getManga(mangaId)?.toSyncManga()
        if (manga == null) {
            log.warn("no manga payload for $mangaId, skipping push of this record")
            return null
        }
        return SyncHistory(
            mangaId = mangaId,
            manga = manga,
            createdAt = createdAt,
            updatedAt = updatedAt,
            chapterId = chapterId,
            page = page,
            scroll = scroll,
            percent = percent,
            chapters = chapters,
            deletedAt = deletedAt,
        )
    }

    private fun SyncCategory.toRecord() = CacheDb.CategoryRecord(
        id = id,
        createdAt = createdAt,
        sortKey = sortKey,
        track = track,
        title = title,
        order = order,
        deletedAt = deletedAt,
        showInLib = showInLib,
    )

    private fun CacheDb.CategoryRecord.toSync() = SyncCategory(
        id = id,
        createdAt = createdAt,
        sortKey = sortKey,
        track = track,
        title = title,
        order = order,
        deletedAt = deletedAt,
        showInLib = showInLib,
    )

    private fun SyncFavourite.toRecord() = CacheDb.FavouriteRecord(
        mangaId = mangaId,
        categoryId = categoryId,
        sortKey = sortKey,
        pinned = pinned,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )

    private fun CacheDb.FavouriteRecord.toSyncFavourite(): SyncFavourite? {
        val manga = mangaPayload(mangaId) ?: run {
            log.warn("no manga payload for favourite $mangaId, skipping push")
            return null
        }
        return SyncFavourite(
            mangaId = mangaId,
            manga = manga,
            categoryId = categoryId,
            sortKey = sortKey,
            pinned = pinned,
            createdAt = createdAt,
            deletedAt = deletedAt,
        )
    }

    private fun mangaPayload(mangaId: Long): SyncManga? =
        db.getSyncMangaJson(mangaId)
            ?.let { runCatching { json.decodeFromString(SyncManga.serializer(), it) }.getOrNull() }
            ?: db.getManga(mangaId)?.toSyncManga()

    private fun Manga.toSyncManga() = SyncManga(
        mangaId = id,
        title = title,
        altTitle = altTitles.firstOrNull() ?: "",
        url = url,
        publicUrl = publicUrl,
        rating = rating,
        contentRating = null,
        coverUrl = coverUrl ?: "",
        largeCoverUrl = largeCoverUrl ?: "",
        tags = emptyList(),
        state = state?.name,
        author = authors.firstOrNull(),
        source = source.name,
    )

    private fun SyncManga.toParserManga(): Manga? {
        val parserSource = runCatching { MangaParserSource.valueOf(source) }.getOrNull() ?: return null
        return Manga(
            id = mangaId,
            title = title,
            altTitles = setOfNotNull(altTitle?.takeIf { it.isNotBlank() }),
            url = url,
            publicUrl = publicUrl,
            rating = rating,
            contentRating = null,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = state?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
            authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
            largeCoverUrl = largeCoverUrl,
            description = null,
            chapters = null,
            source = parserSource,
        )
    }
}
