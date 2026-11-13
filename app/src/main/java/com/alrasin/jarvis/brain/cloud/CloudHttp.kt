package com.alrasin.jarvis.brain.cloud

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
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
        cancellable(client, request) { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw CloudHttpException(name, r.code)
            parse(body)
        }

    /** Runs the blocking call on IO; when the coroutine is cancelled ("stop"), the HTTP call is cancelled too. */
    private suspend fun <T> cancellable(client: OkHttpClient, request: Request, block: suspend (Response) -> T): T =
        coroutineScope {
            val c = client.newCall(request)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { c.cancel() }
            }
            try {
                withContext(Dispatchers.IO) { c.execute().use { block(it) } }
            } finally {
                watcher.cancel()
            }
        }

    /** Each `data:` line goes through [sseText]; non-null pieces go to [onText] at once, so speech starts early. */
    suspend fun stream(
        client: OkHttpClient, request: Request, name: String, sseText: (String) -> String?, onText: (String) -> Unit,
    ): String = cancellable(client, request) { r ->
        if (!r.isSuccessful) throw CloudHttpException(name, r.code)
        val source = r.body?.source() ?: error("$name: empty stream")
        val all = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                // Time limit hit mid-answer: keep the part already spoken instead of failing and answering twice.
                currentCoroutineContext().ensureActive()
                if (all.isBlank()) throw e
                null
            } ?: break
            sseText(line)?.let { all.append(it); onText(it) }
        }
        all.toString().trim()
    }

    /** Opens the TLS connection early (on wake) so the first real request skips the handshake. Errors ignored. */
    fun warm(client: OkHttpClient, url: String, headers: Map<String, String>) {
        runCatching {
            client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().close()
        }
    }
}
