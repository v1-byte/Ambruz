package com.ambruz.webanalyzer.core

import android.util.Base64
import com.ambruz.webanalyzer.analyzer.api.ApiAnalyzer
import com.ambruz.webanalyzer.analyzer.dependency.DependencyAnalyzer
import com.ambruz.webanalyzer.analyzer.domain.DomainAnalyzer
import com.ambruz.webanalyzer.analyzer.resource.ResourceAnalyzer
import com.ambruz.webanalyzer.analyzer.server.ServerAnalyzer
import com.ambruz.webanalyzer.model.AccessLevel
import com.ambruz.webanalyzer.model.Project
import com.ambruz.webanalyzer.model.ProjectStatus
import com.ambruz.webanalyzer.model.ResourceKind
import com.ambruz.webanalyzer.model.ResourceRecord
import com.ambruz.webanalyzer.model.ScanLimits
import com.ambruz.webanalyzer.scanner.UrlScanner
import com.ambruz.webanalyzer.security.UrlGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pipeline scan: HTML + unduh isi resource (teks & binary kecil) agar ZIP tidak kosong.
 */
class ScanPipeline(
    private val scanner: UrlScanner = UrlScanner(),
    private val resources: ResourceAnalyzer = ResourceAnalyzer(),
    private val domains: DomainAnalyzer = DomainAnalyzer(),
    private val apis: ApiAnalyzer = ApiAnalyzer(),
    private val servers: ServerAnalyzer = ServerAnalyzer(),
    private val deps: DependencyAnalyzer = DependencyAnalyzer(),
    private val limits: ScanLimits = ScanLimits(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    fun run(rawUrl: String, access: AccessLevel = AccessLevel.A_PUBLIC): Project {
        val limitations = mutableListOf<String>()
        when (access) {
            AccessLevel.A_PUBLIC -> limitations += "Level A: hanya yang tersedia publik (HTML + aset yang bisa diunduh)."
            AccessLevel.B_AUTHORIZED -> limitations += "Level B: hasil bergantung izin & akses yang diberikan."
            AccessLevel.C_OWNER -> limitations += "Level C: rebuild penuh tetap butuh source/DB milik pemilik."
        }

        val page = scanner.scan(rawUrl)
        if (page.error != null) {
            return Project(
                sourceUrl = rawUrl,
                finalUrl = page.finalUrl,
                accessLevel = access,
                status = ProjectStatus.ERROR,
                limitations = limitations + listOf("Scan gagal: ${page.error}")
            )
        }

        val html = page.bodySnippet.orEmpty()
        var res = resources.extractFromHtml(page.finalUrl, html).take(limits.maxResources)

        // Halaman utama sebagai resource HTML dengan isi penuh
        val mainHtml = ResourceRecord(
            url = page.finalUrl,
            kind = ResourceKind.HTML,
            contentType = page.contentType ?: "text/html",
            sizeBytes = html.toByteArray().size.toLong(),
            statusCode = page.statusCode,
            fetched = html.isNotEmpty(),
            discoveredFrom = page.finalUrl,
            textContent = html.take(MAX_TEXT_CHARS)
        )

        // Unduh isi setiap resource (batas ukuran & jumlah)
        res = listOf(mainHtml) + res.map { fetchOne(it) }

        val dom = domains.analyze(page.finalUrl, res)
        val endpoints = apis.findInText(page.finalUrl, html)
        val serverObs = servers.fromHeaders(page.finalUrl, page.statusCode, page.headers)
        limitations += serverObs.notes
        if (page.fragment != null) {
            limitations += "Fragmen SPA (#${page.fragment}) tidak dikirim ke server HTTP."
        }
        val fetchedN = res.count { it.fetched }
        val bytesN = res.sumOf { it.sizeBytes ?: 0L }
        limitations += "Resource terunduh: $fetchedN / ${res.size} (~${bytesN / 1024} KB). SPA/lazy-load mungkin tidak lengkap tanpa navigasi."
        limitations += "Dependency edges: ${deps.build(page.finalUrl, res, endpoints).size}"

        return Project(
            sourceUrl = rawUrl,
            finalUrl = page.finalUrl,
            accessLevel = access,
            status = ProjectStatus.ANALYZED,
            resources = res,
            domains = dom,
            endpoints = endpoints,
            limitations = limitations,
            notes = "status=${page.statusCode}; type=${page.contentType}; fetched=$fetchedN"
        )
    }

    private fun fetchOne(r: ResourceRecord): ResourceRecord {
        if (r.url == r.discoveredFrom && r.textContent != null) return r
        val ok = UrlGuard.validateHttpsUrl(r.url)
        if (ok.isFailure) {
            return r.copy(error = ok.exceptionOrNull()?.message ?: "URL ditolak", fetched = false)
        }
        return try {
            val req = Request.Builder()
                .url(r.url)
                .header("User-Agent", UrlScanner.USER_AGENT)
                .get()
                .build()
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return r.copy(
                        statusCode = response.code,
                        contentType = response.header("Content-Type"),
                        error = "HTTP ${response.code}",
                        fetched = false
                    )
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: r.contentType
                val kind = r.kind
                val isText = isTextual(ct, kind, r.url)
                if (bytes.size > limits.maxBodyBytes) {
                    return r.copy(
                        contentType = ct,
                        sizeBytes = bytes.size.toLong(),
                        statusCode = response.code,
                        fetched = false,
                        error = "Terlalu besar (>${limits.maxBodyBytes} byte)"
                    )
                }
                if (isText) {
                    val text = bytes.toString(Charsets.UTF_8).take(MAX_TEXT_CHARS)
                    r.copy(
                        contentType = ct,
                        sizeBytes = bytes.size.toLong(),
                        statusCode = response.code,
                        fetched = true,
                        textContent = text,
                        error = null
                    )
                } else if (bytes.size <= MAX_BINARY_BYTES) {
                    r.copy(
                        contentType = ct,
                        sizeBytes = bytes.size.toLong(),
                        statusCode = response.code,
                        fetched = true,
                        base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        error = null
                    )
                } else {
                    r.copy(
                        contentType = ct,
                        sizeBytes = bytes.size.toLong(),
                        statusCode = response.code,
                        fetched = false,
                        error = "Binary terlalu besar untuk inline (>${MAX_BINARY_BYTES} byte)"
                    )
                }
            }
        } catch (e: Exception) {
            r.copy(error = e.message ?: "fetch gagal", fetched = false)
        }
    }

    private fun isTextual(ct: String?, kind: ResourceKind, url: String): Boolean {
        val c = (ct ?: "").lowercase()
        val u = url.lowercase()
        if (c.contains("text/") || c.contains("json") || c.contains("javascript") ||
            c.contains("xml") || c.contains("svg")
        ) return true
        return when (kind) {
            ResourceKind.HTML, ResourceKind.CSS, ResourceKind.JAVASCRIPT, ResourceKind.MANIFEST -> true
            else -> u.endsWith(".js") || u.endsWith(".css") || u.endsWith(".html") ||
                u.endsWith(".json") || u.endsWith(".svg") || u.endsWith(".xml") || u.endsWith(".txt")
        }
    }

    companion object {
        private const val MAX_TEXT_CHARS = 1_500_000
        private const val MAX_BINARY_BYTES = 512 * 1024 // 512KB per gambar/font inline
    }
}
