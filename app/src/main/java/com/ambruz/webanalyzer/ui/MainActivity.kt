package com.ambruz.webanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ambruz.webanalyzer.AmbruzApp
import com.ambruz.webanalyzer.R
import com.ambruz.webanalyzer.core.ScanPipeline
import com.ambruz.webanalyzer.databinding.ActivityMainBinding
import com.ambruz.webanalyzer.model.AccessLevel
import com.ambruz.webanalyzer.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val pipeline = ScanPipeline()
    private var projects: List<Project> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.accessLevel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.access_level_a),
                getString(R.string.access_level_b),
                getString(R.string.access_level_c)
            )
        )

        binding.projectList.layoutManager = LinearLayoutManager(this)
        binding.btnScan.setOnClickListener { startScan() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                else -> false
            }
        }
        refreshList()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.menu_browser))
        menu.add(0, 2, 0, getString(R.string.menu_settings))
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, BrowserActivity::class.java))
            2 -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    private fun accessFromSpinner(): AccessLevel = when (binding.accessLevel.selectedItemPosition) {
        1 -> AccessLevel.B_AUTHORIZED
        2 -> AccessLevel.C_OWNER
        else -> AccessLevel.A_PUBLIC
    }

    private fun startScan() {
        val url = binding.inputUrl.text?.toString().orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "Masukkan URL HTTPS", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnScan.isEnabled = false
        binding.statusText.text = "Memindai…"
        val level = accessFromSpinner()
        lifecycleScope.launch {
            val project = withContext(Dispatchers.IO) {
                pipeline.run(url, level).also {
                    (application as AmbruzApp).projects.save(it)
                }
            }
            binding.btnScan.isEnabled = true
            binding.statusText.text = when (project.status) {
                com.ambruz.webanalyzer.model.ProjectStatus.ANALYZED ->
                    "Selesai · ${project.resources.size} resource · ${project.endpoints.size} endpoint"
                else -> "Error / terbatas — lihat detail proyek"
            }
            refreshList()
            startActivity(
                Intent(this@MainActivity, ProjectDetailActivity::class.java)
                    .putExtra(ProjectDetailActivity.EXTRA_ID, project.id)
            )
        }
    }

    private fun refreshList() {
        projects = (application as AmbruzApp).projects.list()
        binding.projectList.adapter = ProjectAdapter(projects) { p ->
            startActivity(
                Intent(this, ProjectDetailActivity::class.java)
                    .putExtra(ProjectDetailActivity.EXTRA_ID, p.id)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshList()
    }
}

class ProjectAdapter(
    private val items: List<Project>,
    private val onClick: (Project) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ProjectAdapter.VH>() {
    class VH(val view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_project, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.view.findViewById<android.widget.TextView>(R.id.itemUrl).text = p.sourceUrl
        holder.view.findViewById<android.widget.TextView>(R.id.itemMeta).text =
            "${p.status} · ${p.accessLevel} · res=${p.resources.size}"
        holder.view.setOnClickListener { onClick(p) }
    }
}
