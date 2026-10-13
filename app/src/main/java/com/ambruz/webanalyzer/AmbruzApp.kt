package com.ambruz.webanalyzer

import android.app.Application
import com.ambruz.webanalyzer.storage.ProjectRepository

class AmbruzApp : Application() {
    lateinit var projects: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        projects = ProjectRepository(this)
    }
}
