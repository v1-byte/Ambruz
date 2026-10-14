package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ambruz.webanalyzer.core.SessionBus
import com.ambruz.webanalyzer.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            web = binding.shellWeb

            val s = web.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.allowFileAccess = true
            s.allowContentAccess = true
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.mediaPlaybackRequiresUserGesture = true
            web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // keep shell alive
                }
            }

            web.addJavascriptInterface(AmbrusBridge(this, lifecycleScope), "Android")
            SessionBus.listener = { payload: JSONObject ->
                try {
                    if (!isFinishing) {
                        runOnUiThread {
                            try {
                                if (::web.isInitialized) {
                                    val arg = JSONObject.quote(payload.toString())
                                    evaluateJs("try{window.Ambrus&&window.Ambrus.emit&&window.Ambrus.emit($arg)}catch(e){}")
                                }
                            } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) { }
            }

            web.loadUrl("file:///android_asset/ambrus/index.html")
        } catch (e: Exception) {
            finish()
        }
    }

    fun evaluateJs(script: String) {
        try {
            if (!::web.isInitialized) return
            web.evaluateJavascript(script, null)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        try {
            SessionBus.listener = null
            if (::web.isInitialized) {
                web.loadUrl("about:blank")
                web.stopLoading()
                web.destroy()
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        try {
            if (::web.isInitialized && web.canGoBack()) web.goBack()
            else super.onBackPressed()
        } catch (_: Exception) {
            super.onBackPressed()
        }
    }
}
