package dev.futonweb.api

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource

// All 64-bit ids cross the /api boundary as strings: they exceed
// Number.MAX_SAFE_INTEGER and JSON.parse would corrupt them (see SPEC).

@Serializable
data class SourceDto(
    val name: String,
    val title: String,
    val locale: String,
    val broken: Boolean,
)

@Serializable
data class MangaDto(
    val id: String,
    val title: String,
    val altTitles: List<String>,
    val coverUrl: String?,
    val largeCoverUrl: String?,
    val publicUrl: String,
    val rating: Float,
    val state: String?,
    val authors: List<String>,
    val tags: List<String>,
    val description: String?,
    val source: String,
    val chapters: List<ChapterDto>? = null,
)

@Serializable
data class ChapterDto(
    val id: String,
    val title: String?,
    val number: Float,
    val volume: Int,
    val branch: String?,
    val scanlator: String?,
    val uploadDate: Long,
)

@Serializable
data class PageDto(
    val index: Int,
    val imageUrl: String,
)

@Serializable
data class HealthDto(
    val status: String,
    val sources: Int,
    val sync: SyncStatusDto? = null,
)

@Serializable
data class SyncStatusDto(
    val enabled: Boolean,
    val lastSyncAt: Long,
    val lastError: String?,
)

@Serializable
data class HistoryEntryDto(
    val mangaId: String,
    val chapterId: String,
    val page: Int,
    val scroll: Float,
    val percent: Float,
    val chapters: Int,
    val updatedAt: Long,
    val manga: MangaDto?,
)

@Serializable
data class ProgressUpdateDto(
    val chapterId: String,
    val page: Int,
    val scroll: Float = 0f,
    val percent: Float = 0f,
)

fun MangaParserSource.toDto() = SourceDto(
    name = name,
    title = title,
    locale = locale,
    broken = isBroken,
)

fun Manga.toDto(withChapters: Boolean = false) = MangaDto(
    id = id.toString(),
    title = title,
    altTitles = altTitles.toList(),
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
    publicUrl = publicUrl,
    rating = rating,
    state = state?.name,
    authors = authors.toList(),
    tags = tags.map { it.title },
    description = description,
    source = source.name,
    chapters = if (withChapters) chapters?.map { it.toDto() } else null,
)

fun MangaChapter.toDto() = ChapterDto(
    id = id.toString(),
    title = title,
    number = number,
    volume = volume,
    branch = branch,
    scanlator = scanlator,
    uploadDate = uploadDate,
)
