package com.ambruz.webanalyzer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.ambruz.webanalyzer.databinding.ActivityBrowserBinding
import com.ambruz.webanalyzer.security.UrlGuard

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                return UrlGuard.validateHttpsUrl(url).isFailure
            }
        }

        binding.browserGo.setOnClickListener {
            val raw = binding.browserUrl.text?.toString().orEmpty()
            UrlGuard.validateHttpsUrl(raw).onSuccess {
                binding.webView.loadUrl(it.toString())
            }.onFailure {
                binding.browserUrl.error = it.message
            }
        }
    }
}
