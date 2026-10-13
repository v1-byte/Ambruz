package com.ambruz.webanalyzer.storage

import android.content.Context
import com.ambruz.webanalyzer.model.Project
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

class ProjectRepository(context: Context) {
    private val dir = File(context.filesDir, "projects").also { it.mkdirs() }
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(Project::class.java)
    private val listType = Types.newParameterizedType(List::class.java, Project::class.java)
    private val listAdapter = moshi.adapter<List<Project>>(listType)
    private val indexFile = File(dir, "index.json")

    fun list(): List<Project> {
        if (!indexFile.exists()) return emptyList()
        return try {
            listAdapter.fromJson(indexFile.readText()) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Project? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            adapter.fromJson(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(project: Project) {
        File(dir, "${project.id}.json").writeText(adapter.toJson(project))
        val all = list().filterNot { it.id == project.id } + project
        indexFile.writeText(listAdapter.toJson(all.sortedByDescending { it.updatedAt }))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        indexFile.writeText(listAdapter.toJson(list().filterNot { it.id == id }))
    }
}
