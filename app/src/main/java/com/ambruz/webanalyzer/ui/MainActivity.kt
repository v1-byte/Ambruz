package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ambruz.webanalyzer.core.SessionBus
import com.ambruz.webanalyzer.databinding.ActivityMainBinding
import org.json.JSONObject

/**
 * Shell APK: UI Ambrus dari assets + bridge + terima event rekaman preview.
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
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        web.addJavascriptInterface(AmbrusBridge(this, lifecycleScope), "Android")
        SessionBus.listener = { payload: JSONObject ->
            runOnUiThread {
                val arg = JSONObject.quote(payload.toString())
                evaluateJs("window.Ambrus && window.Ambrus.emit && window.Ambrus.emit($arg)")
            }
        }

        web.loadUrl("file:///android_asset/ambrus/index.html")
    }

    fun evaluateJs(script: String) {
        web.evaluateJavascript(script, null)
    }

    override fun onDestroy() {
        if (SessionBus.listener != null) SessionBus.listener = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
