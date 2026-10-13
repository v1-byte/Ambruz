package com.ambruz.webanalyzer.export

import com.ambruz.webanalyzer.model.Project
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M7: ekspor proyek ke ZIP terverifikasi.
 * Tidak menyertakan secret produksi.
 */
class ZipExporter(
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {
    fun export(project: Project, outputZip: File): ExportResult {
        val failures = mutableListOf<String>()
        val checksums = mutableListOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zip ->
            fun put(path: String, content: String) {
                val bytes = content.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
                checksums += "${sha256(bytes)}  $path"
            }

            put("project.json", moshi.adapter(Project::class.java).toJson(project))
            put("README.md", buildReadme(project))
            put("manifest.json", buildManifest(project))
            put("report/analysis.json", """{"projectId":"${project.id}","status":"${project.status}"}""")
            put("report/resources.json", moshi.adapter(List::class.java).toJson(project.resources))
            put("report/domains.json", moshi.adapter(List::class.java).toJson(project.domains))
            put("report/endpoints.json", moshi.adapter(List::class.java).toJson(project.endpoints))
            put("report/limitations.json", moshi.adapter(List::class.java).toJson(project.limitations))
            put("backend/README.md", BACKEND_README)
            put("deployment/README.md", DEPLOY_README)
            put("deployment/deployment-config.example", DEPLOY_EXAMPLE)
            put("checksums/SHA256SUMS.txt", checksums.joinToString("\n") + "\n")
        }
        return ExportResult(outputZip, failures)
    }

    private fun buildReadme(p: Project) = """
# Ambruz Project Export

- ID: ${p.id}
- Source URL: ${p.sourceUrl}
- Final URL: ${p.finalUrl ?: "-"}
- Access level: ${p.accessLevel}
- Status: ${p.status}

## Keterbatasan
${p.limitations.joinToString("\n") { "- $it" }.ifEmpty { "- Tidak ada catatan tambahan" }}

Hanya resource yang tersedia dan diizinkan yang diekspor.
Backend privat tidak disalin hanya dari URL publik.
""".trimIndent()

    private fun buildManifest(p: Project) = """
{
  "tool": "Ambruz",
  "version": "0.1.0-m1",
  "projectId": "${p.id}",
  "sourceUrl": ${jsonStr(p.sourceUrl)},
  "accessLevel": "${p.accessLevel}",
  "resourceCount": ${p.resources.size},
  "domainCount": ${p.domains.size},
  "endpointCount": ${p.endpoints.size}
}
""".trimIndent()

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    data class ExportResult(val file: File, val failures: List<String>)

    companion object {
        private const val BACKEND_README = """
# Backend folder
Tempat spesifikasi API baru dan source Worker/D1.
Kosongkan secret produksi dari arsip ini.
""".trimIndent()
        private const val DEPLOY_README = """
# Deployment
Gunakan Cloudflare Pages / Workers milik Anda.
Lihat deployment-config.example.
""".trimIndent()
        private const val DEPLOY_EXAMPLE = """
# contoh — jangan commit secret
FRONTEND_URL=https://app.example.com
API_URL=https://api.example.com
""".trimIndent()
    }
}
