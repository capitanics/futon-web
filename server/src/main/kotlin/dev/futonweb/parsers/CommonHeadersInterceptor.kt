package dev.futonweb.parsers

import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mergeWith
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.net.IDN

class CommonHeadersInterceptor(
    private val context: MangaLoaderContext,
) : Interceptor {

    override fun intercept(chain: Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java)
        val parser = if (source is MangaParserSource) {
            context.newParserInstance(source)
        } else {
            null
        }
        val headersBuilder = request.headers.newBuilder()
        parser?.getRequestHeaders()?.let {
            headersBuilder.mergeWith(it, replaceExisting = false)
        }
        if (headersBuilder["User-Agent"] == null) {
            headersBuilder["User-Agent"] = context.getDefaultUserAgent()
        }
        if (headersBuilder["Referer"] == null && parser != null) {
            headersBuilder["Referer"] = "https://${IDN.toASCII(parser.domain)}/"
        }
        val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
        return if (parser is Interceptor) {
            parser.interceptSafe(ProxyChain(chain, newRequest))
        } else {
            chain.proceed(newRequest)
        }
    }

    private fun Interceptor.interceptSafe(chain: Chain): Response = runCatchingCancellable {
        intercept(chain)
    }.getOrElse { e ->
        if (e is IOException) throw e
        throw IOException("Error in interceptor: ${e.message}", e)
    }

    private class ProxyChain(
        private val delegate: Chain,
        private val request: Request,
    ) : Chain by delegate {

        override fun request(): Request = request
    }
}
