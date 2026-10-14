package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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

class PreviewRecordActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var recCount: TextView
    private val count = AtomicInteger(0)
    private val seen = HashSet<String>()
    private var recording = true
    private var lastEmitMs = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_preview_record)
            val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

            web = findViewById(R.id.previewWeb)
            urlBar = findViewById(R.id.previewUrl)
            recCount = findViewById(R.id.recCount)

            findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finishRecording(false) }
            findViewById<Button>(R.id.btnFinish).setOnClickListener { finishRecording(true) }
            findViewById<Button>(R.id.btnGo).setOnClickListener { navigate(urlBar.text.toString()) }
            urlBar.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigate(urlBar.text.toString()); true
                } else false
            }

            val s = web.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.loadsImagesAutomatically = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    return UrlGuard.validateHttpsUrl(u).isFailure
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    try {
                        if (url != null) urlBar.setText(url)
                    } catch (_: Exception) { }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Do not extract full HTML (causes OOM/crash). Only note page URL.
                    if (!recording || url == null) return
                    recordResource(url, "text/html", null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    try {
                        val u = request?.url?.toString() ?: return null
                        if (recording && u.startsWith("http")) {
                            recordResource(u, guessMime(u), null)
                        }
                    } catch (_: Exception) { }
                    return null
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) { }
            }

            if (startUrl.isNotBlank()) navigate(startUrl)
        } catch (e: Exception) {
            Toast.makeText(this, "Preview gagal: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun navigate(raw: String) {
        val v = UrlGuard.validateHttpsUrl(raw.trim())
        if (v.isFailure) {
            Toast.makeText(this, v.exceptionOrNull()?.message ?: "URL invalid", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            web.loadUrl(v.getOrThrow().toString())
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordResource(url: String, mime: String, size: Long?) {
        try {
            if (!seen.add(url)) return
            if (seen.size > 500) {
                recording = false
                return
            }
            val n = count.incrementAndGet()
            val now = System.currentTimeMillis()
            runOnUiThread {
                try { recCount.text = "$n resource" } catch (_: Exception) { }
            }
            // throttle bus events
            if (now - lastEmitMs < 40 && n % 5 != 0) return
            lastEmitMs = now
            val o = JSONObject()
                .put("type", "resource")
                .put("url", url.take(2000))
                .put("mime", mime)
                .put("status", "ok")
                .put("path", url.substringAfterLast('/').ifBlank { "index.html" }.take(120))
            if (size != null) o.put("size", size)
            SessionBus.emit(o)
        } catch (_: Exception) { }
    }

    private fun finishRecording(success: Boolean) {
        recording = false
        try {
            if (success) {
                SessionBus.emit(JSONObject().put("type", "progress").put("value", 100).put("message", "Rekaman selesai · ${count.get()} resource"))
                SessionBus.emit(JSONObject().put("type", "done"))
            }
        } catch (_: Exception) { }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            if (::web.isInitialized && web.canGoBack()) web.goBack()
            else finishRecording(false)
        } catch (_: Exception) {
            finish()
        }
    }

    override fun onDestroy() {
        try {
            recording = false
            if (::web.isInitialized) {
                web.stopLoading()
                web.loadUrl("about:blank")
                web.destroy()
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun guessMime(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".css") -> "text/css"
            u.contains(".js") -> "application/javascript"
            u.contains(".json") -> "application/json"
            u.contains(".png") -> "image/png"
            u.contains(".jpg") || u.contains(".jpeg") -> "image/jpeg"
            u.contains(".webp") -> "image/webp"
            u.contains(".woff") -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PROJECT_ID = "project_id"
    }
}
