package com.ambruz.webanalyzer.scanner

import com.ambruz.webanalyzer.model.ScanLimits
import com.ambruz.webanalyzer.security.UrlGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

data class ScanPageResult(
    val inputUrl: String,
    val finalUrl: String,
    val statusCode: Int?,
    val contentType: String?,
    val bodySnippet: String?,
    val headers: Map<String, String>,
    val fragment: String?,
    val error: String?
)

/**
 * M1–M2: validasi URL, ikuti redirect yang diizinkan, catat status.
 * Tidak mengklaim mendapatkan source backend privat.
 */
class UrlScanner(
    private val client: OkHttpClient = defaultClient(),
    private val limits: ScanLimits = ScanLimits()
) {
    fun scan(rawUrl: String): ScanPageResult {
        val validated = UrlGuard.validateHttpsUrl(rawUrl).getOrElse {
            return ScanPageResult(rawUrl, rawUrl, null, null, null, emptyMap(), null, it.message)
        }
        val (base, fragment) = UrlGuard.splitFragment(validated)
        return try {
            val request = Request.Builder()
                .url(base)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body
                val bytes = body?.bytes()
                val limited = if (bytes != null && bytes.size > limits.maxBodyBytes) {
                    bytes.copyOf(limits.maxBodyBytes.toInt())
                } else bytes
                val snippet = limited?.toString(Charsets.UTF_8) // full body up to maxBodyBytes for parsing + export
                val headerMap = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                ScanPageResult(
                    inputUrl = rawUrl,
                    finalUrl = response.request.url.toString(),
                    statusCode = response.code,
                    contentType = response.header("Content-Type"),
                    bodySnippet = snippet,
                    headers = headerMap,
                    fragment = fragment,
                    error = null
                )
            }
        } catch (e: Exception) {
            ScanPageResult(rawUrl, base, null, null, null, emptyMap(), fragment, e.message)
        }
    }

    companion object {
        const val USER_AGENT = "AmbruzAnalyzer/0.1 (+authorized-project-scan)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
