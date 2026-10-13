package com.ambruz.webanalyzer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ambruz.webanalyzer.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivitySettingsBinding.inflate(layoutInflater).root)
    }
}
