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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class AmbrusBridge(
    private val activity: MainActivity,
    private val scope: CoroutineScope
) {
    private val pipeline = ScanPipeline()
    @Volatile
    private var activeId: String? = null

    private fun zipDir(): File {
        val base = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: activity.filesDir
        return File(base, "Ambrus").also { it.mkdirs() }
    }

    private fun emit(payload: JSONObject) {
        val arg = JSONObject.quote(payload.toString())
        val js = "window.Ambrus && window.Ambrus.emit && window.Ambrus.emit($arg)"
        activity.runOnUiThread { activity.evaluateJs(js) }
    }

    @JavascriptInterface
    fun startPreview(payloadJson: String) {
        try {
            val o = JSONObject(payloadJson)
            val url = o.getString("url")
            val projectId = o.optString("projectId")
            activity.runOnUiThread {
                activity.startActivity(
                    Intent(activity, PreviewRecordActivity::class.java)
                        .putExtra(PreviewRecordActivity.EXTRA_URL, url)
                        .putExtra(PreviewRecordActivity.EXTRA_PROJECT_ID, projectId)
                )
            }
        } catch (e: Exception) {
            emit(JSONObject().put("type", "error").put("message", e.message ?: "Preview gagal"))
        }
    }

    @JavascriptInterface
    fun startScan(payloadJson: String) {
        scope.launch {
            try {
                val o = JSONObject(payloadJson)
                val projectId = o.optString("projectId")
                val url = o.getString("url")
                val levelStr = o.optString("level", "A")
                val level = when {
                    levelStr.contains("B") -> AccessLevel.B_AUTHORIZED
                    levelStr.contains("C") -> AccessLevel.C_OWNER
                    else -> AccessLevel.A_PUBLIC
                }
                activeId = projectId
                emit(JSONObject().put("type", "progress").put("value", 5).put("message", "Loading... memindai URL"))
                val project = withContext(Dispatchers.IO) { pipeline.run(url, level) }
                if (activeId != projectId) return@launch
                (activity.application as AmbruzApp).projects.save(
                    project.copy(id = if (projectId.isNotBlank()) projectId else project.id)
                )
                val host = try { java.net.URI(project.finalUrl ?: url).host ?: "" } catch (_: Exception) { "" }
                emit(JSONObject().put("type", "server").put("host", host).put("status", 200).put("headers", JSONObject().put("content-type", "text/html")))
                project.resources.take(300).forEachIndexed { i, r ->
                    if (activeId != projectId) return@launch
                    emit(JSONObject().put("type", "resource").put("url", r.url).put("mime", r.contentType ?: "").put("size", r.sizeBytes ?: JSONObject.NULL).put("status", if (r.error == null) "ok" else "error").put("error", r.error ?: "").put("page", project.finalUrl ?: url).put("path", r.url.substringAfterLast("/").ifBlank { "file" }))
                    if (i % 3 == 0) {
                        val pct = ((i + 1) * 80 / project.resources.size.coerceAtLeast(1)).coerceIn(5, 90)
                        emit(JSONObject().put("type", "progress").put("value", pct).put("message", "Loading resource ${i + 1}..."))
                    }
                }
                project.endpoints.forEach { e ->
                    emit(JSONObject().put("type", "request").put("url", e.url).put("method", e.method).put("status", e.statusCode ?: JSONObject.NULL).put("mime", e.contentType ?: "").put("page", e.triggerPage ?: url))
                }
                emit(JSONObject().put("type", "progress").put("value", 100).put("message", "Selesai - Download full ZIP"))
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
                val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ambrus-export.zip" }
                val out = File(zipDir(), safe)
                out.writeBytes(bytes)
                emit(JSONObject().put("type", "zip_saved").put("path", out.absolutePath).put("fileName", out.name).put("size", out.length()))
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "ZIP disimpan:\n${out.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                emit(JSONObject().put("type", "error").put("message", "Gagal simpan ZIP: ${e.message}"))
            }
        }
    }

    @JavascriptInterface
    fun listZips() {
        scope.launch(Dispatchers.IO) {
            try {
                val files = zipDir().listFiles()?.filter { it.isFile && it.name.endsWith(".zip", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
                val arr = JSONArray()
                files.forEach { f -> arr.put(JSONObject().put("name", f.name).put("path", f.absolutePath).put("size", f.length())) }
                emit(JSONObject().put("type", "zip_list").put("files", arr))
            } catch (e: Exception) {
                emit(JSONObject().put("type", "error").put("message", e.message ?: "listZips gagal"))
            }
        }
    }

    @JavascriptInterface
    fun loadZip(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) {
                    emit(JSONObject().put("type", "error").put("message", "File tidak ditemukan: $path"))
                    return@launch
                }
                var projectJson: String? = null
                val htmlFiles = mutableMapOf<String, String>()
                ZipInputStream(file.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && !name.contains("..")) {
                            val data = zis.readBytes()
                            when {
                                name == "project.json" || name.endsWith("/project.json") -> projectJson = data.toString(Charsets.UTF_8)
                                name.startsWith("website/") && (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".css") || name.endsWith(".js")) -> {
                                    htmlFiles[name.removePrefix("website/")] = data.toString(Charsets.UTF_8)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                val project = try { if (projectJson != null) JSONObject(projectJson!!) else JSONObject() } catch (_: Exception) { JSONObject() }
                if (!project.has("id")) project.put("id", "zip_" + System.currentTimeMillis().toString(36))
                if (!project.has("url")) project.put("url", "file://" + file.name)
                project.put("status", "selesai")
                project.put("demo", false)
                project.put("exportStatus", "loaded")
                project.put("zipPath", file.absolutePath)
                val indexHtml = htmlFiles["index.html"] ?: htmlFiles.entries.firstOrNull { it.key.endsWith(".html") }?.value
                if (indexHtml != null) {
                    val edits = project.optJSONObject("edits") ?: JSONObject()
                    edits.put("htmlDraft", indexHtml.take(200000))
                    project.put("edits", edits)
                }
                if (htmlFiles.isNotEmpty()) {
                    val filesObj = JSONObject()
                    htmlFiles.forEach { (k, v) -> filesObj.put(k, v.take(100000)) }
                    project.put("websiteFiles", filesObj)
                }
                emit(JSONObject().put("type", "zip_loaded").put("project", project.toString()).put("path", file.absolutePath))
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "ZIP dimuat - buka Editor", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                emit(JSONObject().put("type", "error").put("message", "Load ZIP gagal: ${e.message}"))
            }
        }
    }
}
