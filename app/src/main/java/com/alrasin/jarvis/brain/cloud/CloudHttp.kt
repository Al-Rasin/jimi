package com.alrasin.jarvis.brain.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** A cloud model answered with an HTTP error. 429 = free limit reached (D-063). */
class CloudHttpException(val provider: String, val code: Int) : IllegalStateException("$provider HTTP $code")

/** One HTTP path for every cloud model (D-063 §L.6): JSON post, and server-sent events that hand out text pieces. */
object CloudHttp {
    private val JSON = "application/json".toMediaType()

    fun client(connectSeconds: Long, callSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectSeconds, TimeUnit.SECONDS).callTimeout(callSeconds, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES)).build()

    fun post(url: String, headers: Map<String, String>, body: String): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body.toRequestBody(JSON)).build()

    suspend fun call(client: OkHttpClient, request: Request, name: String, parse: (String) -> String): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw CloudHttpException(name, r.code)
                parse(body)
            }
        }

    /** Each `data:` line goes through [sseText]; non-null pieces go to [onText] at once, so speech starts early. */
    suspend fun stream(
        client: OkHttpClient, request: Request, name: String, sseText: (String) -> String?, onText: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw CloudHttpException(name, r.code)
            val source = r.body?.source() ?: error("$name: empty stream")
            val all = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                sseText(line)?.let { all.append(it); onText(it) }
            }
            all.toString().trim()
        }
    }

    /** Opens the TLS connection early (on wake) so the first real request skips the handshake. Errors ignored. */
    fun warm(client: OkHttpClient, url: String, headers: Map<String, String>) {
        runCatching {
            client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().close()
        }
    }
}
