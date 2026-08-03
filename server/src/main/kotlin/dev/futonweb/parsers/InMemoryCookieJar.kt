package dev.futonweb.parsers

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class InMemoryCookieJar : CookieJar {

    private val cache = ConcurrentHashMap<CookieKey, Cookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val time = System.currentTimeMillis()
        return cache.values.filter { it.matches(url) && it.expiresAt >= time }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach {
            cache[CookieKey(url.host, it.name)] = it
        }
    }

    private data class CookieKey(
        val host: String,
        val name: String,
    )
}
