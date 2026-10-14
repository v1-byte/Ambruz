package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ambruz.webanalyzer.R
import com.ambruz.webanalyzer.core.SessionBus
import com.ambruz.webanalyzer.security.UrlGuard
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Preview web + merekam resource yang dimuat saat navigasi (alur sebelum copy/export).
 * Tidak memblokir request — hanya mengamati URL yang dimuat WebView.
 */
class PreviewRecordActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var recCount: TextView
    private lateinit var recStatus: TextView
    private val count = AtomicInteger(0)
    private val seen = HashSet<String>()
    private var projectId: String = ""
    private var recording = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview_record)

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.previewWeb)
        urlBar = findViewById(R.id.previewUrl)
        recCount = findViewById(R.id.recCount)
        recStatus = findViewById(R.id.recStatus)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finishRecording(false) }
        findViewById<Button>(R.id.btnFinish).setOnClickListener { finishRecording(true) }
        findViewById<Button>(R.id.btnGo).setOnClickListener { navigate(urlBar.text.toString()) }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(urlBar.text.toString()); true
            } else false
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                val v = UrlGuard.validateHttpsUrl(u)
                if (v.isFailure) {
                    Toast.makeText(this@PreviewRecordActivity, v.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) urlBar.setText(url)
                if (recording && url != null) {
                    SessionBus.emit(
                        JSONObject()
                            .put("type", "progress")
                            .put("value", 10)
                            .put("message", "Navigasi: $url")
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!recording || url == null) return
                // Cuplikan HTML halaman (terbatas) untuk analisis struktur
                view?.evaluateJavascript(
                    "(function(){try{return document.documentElement.outerHTML.slice(0,120000)}catch(e){return ''}})()"
                ) { html ->
                    val cleaned = html?.trim()?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                        ?: return@evaluateJavascript
                    if (cleaned.isBlank()) return@evaluateJavascript
                    recordResource(url, "text/html", cleaned.length.toLong(), cleaned)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (recording && u.startsWith("http")) {
                    val mime = guessMime(u)
                    recordResource(u, mime, null, null)
                    if (request.method != "GET" || looksLikeApi(u, mime)) {
                        SessionBus.emit(
                            JSONObject()
                                .put("type", "request")
                                .put("url", u)
                                .put("method", request.method ?: "GET")
                                .put("mime", mime)
                                .put("page", view?.url ?: u)
                        )
                    }
                }
                return null // biarkan WebView memuat normal
            }
        }

        SessionBus.emit(
            JSONObject()
                .put("type", "progress")
                .put("value", 1)
                .put("message", "Mode preview+rekam aktif — navigasikan situs, lalu Selesai & Copy")
        )

        if (startUrl.isNotBlank()) navigate(startUrl)
    }

    private fun navigate(raw: String) {
        val v = UrlGuard.validateHttpsUrl(raw.trim())
        if (v.isFailure) {
            Toast.makeText(this, v.exceptionOrNull()?.message ?: "URL invalid", Toast.LENGTH_SHORT).show()
            return
        }
        web.loadUrl(v.getOrThrow().toString())
    }

    private fun recordResource(url: String, mime: String, size: Long?, content: String?) {
        if (!seen.add(url)) return
        val n = count.incrementAndGet()
        runOnUiThread { recCount.text = "$n resource" }
        val o = JSONObject()
            .put("type", "resource")
            .put("url", url)
            .put("mime", mime)
            .put("status", "ok")
            .put("page", web.url ?: url)
            .put("path", url.substringAfterLast('/').ifBlank { "index.html" }.take(120))
        if (size != null) o.put("size", size)
        if (content != null) o.put("content", content.take(100_000))
        SessionBus.emit(o)
    }

    private fun finishRecording(success: Boolean) {
        recording = false
        if (success) {
            SessionBus.emit(JSONObject().put("type", "progress").put("value", 100).put("message", "Rekaman selesai · ${count.get()} resource"))
            SessionBus.emit(JSONObject().put("type", "done"))
            Toast.makeText(this, "Selesai merekam ${count.get()} resource", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finishRecording(false)
    }

    private fun guessMime(url: String): String {
        val u = url.lowercase()
        return when {
            u.endsWith(".css") || u.contains(".css?") -> "text/css"
            u.endsWith(".js") || u.contains(".js?") -> "application/javascript"
            u.endsWith(".json") || u.contains(".json?") -> "application/json"
            u.endsWith(".png") -> "image/png"
            u.endsWith(".jpg") || u.endsWith(".jpeg") -> "image/jpeg"
            u.endsWith(".webp") -> "image/webp"
            u.endsWith(".svg") -> "image/svg+xml"
            u.endsWith(".woff2") || u.endsWith(".woff") -> "font/woff2"
            u.endsWith(".mp4") -> "video/mp4"
            u.endsWith(".html") || u.endsWith("/") -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun looksLikeApi(url: String, mime: String): Boolean {
        val u = url.lowercase()
        return mime.contains("json") || "/api/" in u || "graphql" in u || u.contains("/v1/") || u.contains("/v2/")
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
