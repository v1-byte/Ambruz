package com.ambruz.webanalyzer.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.ambruz.webanalyzer.AmbruzApp
import com.ambruz.webanalyzer.core.ScanPipeline
import com.ambruz.webanalyzer.model.AccessLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Bridge ke UI assets/ambrus — kontrak di app.js:
 * Android.startScan / stopScan / openUrl / saveZip
 * Native → Ambrus.emit({ type: 'resource'|'done'|... })
 */
class AmbrusBridge(
    private val activity: MainActivity,
    private val scope: CoroutineScope
) {
    private val pipeline = ScanPipeline()
    @Volatile private var activeId: String? = null

    @JavascriptInterface
    fun startScan(payloadJson: String) {
        scope.launch {
            try {
                val o = JSONObject(payloadJson)
                val projectId = o.optString("projectId")
                val url = o.getString("url")
                val levelStr = o.optString("level", "A")
                val level = when {
                    levelStr.contains("B") || levelStr == "B" -> AccessLevel.B_AUTHORIZED
                    levelStr.contains("C") || levelStr == "C" -> AccessLevel.C_OWNER
                    else -> AccessLevel.A_PUBLIC
                }
                activeId = projectId
                emit(JSONObject().put("type", "progress").put("value", 5).put("message", "Scan native dimulai…"))

                val project = withContext(Dispatchers.IO) { pipeline.run(url, level) }
                if (activeId != projectId) return@launch

                (activity.application as AmbruzApp).projects.save(
                    project.copy(id = if (projectId.isNotBlank()) projectId else project.id)
                )

                emit(
                    JSONObject()
                        .put("type", "server")
                        .put("host", try { java.net.URI(project.finalUrl ?: url).host } catch (_: Exception) { "" })
                        .put("status", 200)
                        .put("headers", JSONObject().put("content-type", "text/html"))
                )

                project.resources.forEachIndexed { i, r ->
                    if (activeId != projectId) return@launch
                    emit(
                        JSONObject()
                            .put("type", "resource")
                            .put("url", r.url)
                            .put("mime", r.contentType ?: "")
                            .put("size", r.sizeBytes ?: JSONObject.NULL)
                            .put("status", if (r.error == null) "ok" else "error")
                            .put("error", r.error ?: "")
                            .put("page", project.finalUrl ?: url)
                            .put("path", r.url.substringAfterLast('/').ifBlank { "file" })
                    )
                    if (i % 5 == 0) {
                        val pct = ((i + 1) * 80 / project.resources.size.coerceAtLeast(1)).coerceIn(5, 90)
                        emit(JSONObject().put("type", "progress").put("value", pct))
                    }
                }

                project.endpoints.forEach { e ->
                    emit(
                        JSONObject()
                            .put("type", "request")
                            .put("url", e.url)
                            .put("method", e.method)
                            .put("status", e.statusCode ?: JSONObject.NULL)
                            .put("mime", e.contentType ?: "")
                            .put("page", e.triggerPage ?: url)
                    )
                }

                emit(JSONObject().put("type", "progress").put("value", 100).put("message", "Selesai"))
                emit(JSONObject().put("type", "done"))
            } catch (e: Exception) {
                emit(JSONObject().put("type", "error").put("message", e.message ?: "Scan gagal"))
            }
        }
    }

    @JavascriptInterface
    fun stopScan(projectId: String) {
        activeId = null
        emit(JSONObject().put("type", "progress").put("value", 0).put("message", "Dihentikan"))
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun saveZip(base64: String, fileName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
                val out = File(dir, safe)
                out.writeBytes(bytes)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "ZIP: ${out.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Gagal ZIP: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun emit(payload: JSONObject) {
        // Ambrus.emit menerima objek atau string JSON
        val arg = JSONObject.quote(payload.toString())
        val js = "window.Ambrus && window.Ambrus.emit && window.Ambrus.emit($arg)"
        activity.runOnUiThread { activity.evaluateJs(js) }
    }
}
