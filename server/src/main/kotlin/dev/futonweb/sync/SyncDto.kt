package dev.futonweb.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire types of the Kotatsu sync protocol. Field shapes taken from a real
// payload captured from Futon (docs/fixtures/history-package.json).

@Serializable
data class SyncTag(
    @SerialName("tag_id") val tagId: Long,
    val title: String,
    val key: String,
    val source: String,
)

@Serializable
data class SyncManga(
    @SerialName("manga_id") val mangaId: Long,
    val title: String,
    @SerialName("alt_title") val altTitle: String? = null,
    val url: String,
    @SerialName("public_url") val publicUrl: String = "",
    val rating: Float = -1f,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("large_cover_url") val largeCoverUrl: String? = null,
    val tags: List<SyncTag> = emptyList(),
    val state: String? = null,
    val author: String? = null,
    val source: String,
)

@Serializable
data class SyncHistory(
    @SerialName("manga_id") val mangaId: Long,
    val manga: SyncManga,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("chapter_id") val chapterId: Long,
    val page: Int,
    val scroll: Float = 0f,
    val percent: Float = 0f,
    val chapters: Int = -1,
    @SerialName("deleted_at") val deletedAt: Long = 0,
)

@Serializable
data class SyncHistoryPackage(
    val history: List<SyncHistory>,
    val timestamp: Long? = null,
)

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val token: String)
