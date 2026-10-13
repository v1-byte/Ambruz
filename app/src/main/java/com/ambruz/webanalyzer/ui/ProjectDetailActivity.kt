package com.ambruz.webanalyzer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ambruz.webanalyzer.AmbruzApp
import com.ambruz.webanalyzer.databinding.ActivityProjectDetailBinding
import com.ambruz.webanalyzer.export.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProjectDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_ID) ?: run {
            finish(); return
        }
        val project = (application as AmbruzApp).projects.get(id) ?: run {
            Toast.makeText(this, "Proyek tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        binding.detailTitle.text = project.sourceUrl
        binding.detailSummary.text =
            "Status: ${project.status}\nAkses: ${project.accessLevel}\nFinal: ${project.finalUrl}\n" +
                "Resources: ${project.resources.size} · Domains: ${project.domains.size} · Endpoints: ${project.endpoints.size}"

        val body = buildString {
            appendLine("=== LIMITATIONS ===")
            project.limitations.forEach { appendLine("- $it") }
            appendLine()
            appendLine("=== DOMAINS ===")
            project.domains.forEach { appendLine("${it.hostname} | ${it.category} | ${it.discoverySource}") }
            appendLine()
            appendLine("=== ENDPOINTS ===")
            project.endpoints.take(50).forEach { appendLine("${it.method} ${it.url} [${it.classification}]") }
            appendLine()
            appendLine("=== RESOURCES (max 80) ===")
            project.resources.take(80).forEach { appendLine("${it.kind} ${it.url}") }
        }
        binding.detailBody.text = body

        binding.btnExport.setOnClickListener {
            lifecycleScope.launch {
                val out = withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "ambruz-${project.id.take(8)}.zip")
                    ZipExporter().export(project, file)
                }
                Toast.makeText(
                    this@ProjectDetailActivity,
                    "ZIP: ${out.file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "project_id"
    }
}
