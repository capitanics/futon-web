package dev.futonweb

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Write-through disk cache for proxied images. Page re-reads and covers are
 * served from disk instead of re-hitting the manga CDN every time.
 */
class ImageCache(private val dir: File, private val maxBytes: Long) {

    init {
        dir.mkdirs()
    }

    data class Entry(val file: File, val contentType: String)

    fun get(url: String): Entry? {
        val base = key(url)
        val data = File(dir, "$base.bin")
        val meta = File(dir, "$base.ct")
        if (!data.isFile || !meta.isFile) return null
        data.setLastModified(System.currentTimeMillis())
        return Entry(data, meta.readText())
    }

    fun put(url: String, contentType: String, body: InputStream): Entry {
        val base = key(url)
        val tmp = File(dir, "$base.tmp")
        val data = File(dir, "$base.bin")
        tmp.outputStream().use { out -> body.copyTo(out) }
        tmp.renameTo(data)
        File(dir, "$base.ct").writeText(contentType)
        evictIfNeeded()
        return Entry(data, contentType)
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun evictIfNeeded() {
        val files = dir.listFiles { f -> f.extension == "bin" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            File(f.parentFile, f.nameWithoutExtension + ".ct").delete()
            f.delete()
        }
    }
}
