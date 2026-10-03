package io.ledgerlens.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.io.IOException
import org.json.JSONObject

class ApiException(val host: String, val status: Int, val apiCode: Int? = null, val retryAfterSeconds: Long? = null) : IOException(
    "$host: HTTP $status" + (retryAfterSeconds?.let { " (retry after ${it}s)" } ?: "")
)

open class Http(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()) {
    private val cooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    open suspend fun get(url: String, headers: Map<String,String> = emptyMap()) = request(url, null, headers)
    open suspend fun post(url: String, body: String, headers: Map<String,String> = emptyMap()) = request(url, body, headers)
    /** Submission is attempted once. A timeout is an unknown result, never an automatic second transfer. */
    open suspend fun submit(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://"))
        val request = Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType())).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build().newCall(request).execute().use { response ->
            val payload = response.body ?: error("${request.url.host}: empty submission response")
            check(payload.contentLength() <= 1_000_000) { "${request.url.host}: submission response too large" }
            val source = payload.source(); source.request(1_000_001)
            check(source.buffer.size <= 1_000_000) { "${request.url.host}: submission response too large" }
            val text = source.readUtf8()
            if (!response.isSuccessful) throw ApiException(request.url.host, response.code)
            text
        }
    }
    private suspend fun request(url: String, body: String?, headers: Map<String,String>): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://"))
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        val request = builder.build()
        val host = request.url.host
        repeat(3) { attempt ->
            val wait = (cooldowns[host] ?: 0) - System.currentTimeMillis()
            if (wait > 5_000) throw ApiException(host, 429, retryAfterSeconds = (wait + 999) / 1000)
            if (wait > 0) delay(wait)
            var failure: ApiException? = null
            client.newCall(request).execute().use { response ->
                val payload = response.body ?: error("$host: empty API response")
                check(payload.contentLength() <= 10_000_000) { "$host: API response too large" }
                val source = payload.source()
                source.request(10_000_001)
                check(source.buffer.size <= 10_000_000) { "$host: API response too large" }
                val text = source.readUtf8()
                if (response.isSuccessful) return@withContext text
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(1)
                    ?: response.header("Retry-After")?.let { date -> runCatching {
                        val at = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(date)!!.time
                        ((at - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(1)
                    }.getOrNull() }
                val code = runCatching { JSONObject(text).getInt("code") }.getOrNull()
                failure = ApiException(host, response.code, code, retryAfter)
                if (response.code == 429) cooldowns[host] = System.currentTimeMillis() + (retryAfter ?: 2) * 1000
            }
            val error = failure!!
            if (attempt == 2 || (error.status != 429 && error.status !in 500..599) || (error.retryAfterSeconds ?: 0) > 5) throw error
            delay(500L * (1 shl attempt))
        }
        error("$host: request failed")
    }
}
