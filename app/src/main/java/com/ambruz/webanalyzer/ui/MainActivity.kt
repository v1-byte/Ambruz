package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ambruz.webanalyzer.databinding.ActivityMainBinding

/**
 * Shell APK: memuat UI Ambrus dari assets (tampilan utama).
 * Bridge window.Android → native scan / simpan ZIP.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        web = binding.shellWeb

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        // Assets lokal — tidak izinkan akses file:// sewenang-wenang dari konten remote
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                // Biarkan navigasi hash SPA di dalam assets
                if (u.startsWith("file:///android_asset/")) return false
                return false
            }
        }

        web.addJavascriptInterface(AmbrusBridge(this, lifecycleScope), "Android")
        web.loadUrl("file:///android_asset/ambrus/index.html")
    }

    fun evaluateJs(script: String) {
        web.evaluateJavascript(script, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
